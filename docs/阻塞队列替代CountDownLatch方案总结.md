# 阻塞队列替代 CountDownLatch 实现音频顺序推理控制

## 📋 文档概述

本文档总结了使用阻塞队列替代 CountDownLatch 实现音频分段顺序推理控制的技术方案,包括核心原理、流程图和实现方案。

---

## 🎯 核心目标

**原始需求**: 用阻塞队列控制音频一段一段地发送给 MuseTalk 推理,替代原有的 CountDownLatch + 状态变量机制。

**关键要求**:
- 音频必须按顺序处理(段1完成后才能处理段2)
- 视频帧实时推送到前端(不等待)
- 简化代码,避免手动管理状态

---

## ❓ 核心问答

### Q1: 阻塞队列里存的是什么?

**答**: 阻塞队列存储的是**完成信号**(字符串),不是音频数据或任务对象。

```java
BlockingQueue<String> completionQueue = new LinkedBlockingQueue<>(1);
// 队列内容: ["completed"] 或 ["error"] 或 []
```

**为什么不存音频数据?**
- 音频文件很大(几MB),浪费内存
- MuseTalk 在服务器端,可以直接通过文件路径读取
- 只需发送路径字符串,不需要传输整个文件

---

### Q2: 音频是怎么发送给 MuseTalk 的?

**答**: 通过 WebSocket **直接发送音频路径**(JSON格式),不经过阻塞队列。

```java
// 发送 JSON 请求
{
  "audio_path": "/path/to/audio1.wav",
  "avatar": "sad",
  "options": {...}
}
```

MuseTalk 收到路径后,自己读取音频文件进行推理。

---

### Q3: 阻塞和唤醒是怎么实现的?

**答**: 
- **阻塞**: 主线程调用 `completionQueue.poll(60秒)`,队列为空时阻塞
- **唤醒**: MuseTalk 推理完成后,通过 WebSocket 回调 `completionQueue.offer("completed")`,唤醒主线程

```java
// 主线程(消费者)
String signal = completionQueue.poll(60, TimeUnit.SECONDS);  // 阻塞

// MuseTalk 回调(生产者)
completionQueue.offer("completed");  // 唤醒
```

---

### Q4: 为什么后台线程取走任务后,主线程还能阻塞?

**答**: 主线程不是阻塞在任务队列,而是阻塞在**任务对象内部的结果队列**。

```java
AudioSegmentTask task = new AudioSegmentTask(audio, i);

// 步骤1: 放入任务队列(不阻塞)
taskQueue.offer(task);

// 步骤2: 阻塞在任务的结果队列
ProcessResult result = task.waitForResult(60);  // 👈 阻塞在这里
// 内部: task.resultQueue.poll(60秒)
```

**关键**: 主线程和后台线程都持有同一个 `task` 对象的引用,后台线程取走任务后,主线程仍然可以访问这个对象的 `resultQueue`。

---

### Q5: 谁是生产者,谁是消费者?

**答**: 
- **生产者**: MuseTalk 端(生产"完成信号")
- **消费者**: 音频发送端(消费"完成信号")

```
消费者(主线程): poll() → 队列空 → 阻塞
生产者(MuseTalk): offer("completed") → 唤醒消费者
```

---

## 🔄 完整数据流程

### 流程图

```
═══════════════════════════════════════════════════════════════
【音频顺序推理流程】
═══════════════════════════════════════════════════════════════

主线程                 completionQueue         MuseTalk服务
  │                         │                      │
  │ for (音频列表) {                               │
  │                         │                      │
  │  // 1. 发送音频路径                            │
  │  sendAudioRequest("音频1.wav", "sad")          │
  │  ──────────────────────────────────────────→  │
  │                         │               接收路径
  │                         │               读取文件
  │                         │               开始推理
  │                         │                      │
  │  // 2. 阻塞等待完成信号                        │
  │  poll(60秒)             │                      │
  │  【阻塞】 ⏸️            │                      │
  │     ║                  │                      │
  │     ║                  │               推理中...
  │     ║                  │               生成视频帧
  │     ║                  │               ──→ 前端
  │     ║                  │               ──→ 前端
  │     ║                  │                      │
  │     ║                  │               推理完成!
  │     ║                  │                      │
  │     ║                  │   WebSocket回调:      │
  │     ║                  │   {"status":"completed"}
  │     ║                  │   ←──────────────────│
  │     ║                  │                      │
  │     ║          offer("completed")             │
  │     ║          ←────────────                  │
  │     ║                 [completed]             │
  │     ║                  │                      │
  │  poll() 返回           │                      │
  │  【唤醒】 ▶️           │                      │
  │                        [] (队列自动清空)      │
  │                         │                      │
  │  // 3. 继续下一个音频                          │
  │  sendAudioRequest("音频2.wav", "happy")        │
  │  ──────────────────────────────────────────→  │
  │                         │                      │
  │  poll(60秒)             │                      │
  │  【再次阻塞】 ⏸️        │                      │
  │                         │                      │
```

---

### 队列状态变化

| 时间 | 操作 | completionQueue | 主线程状态 |
|------|------|----------------|-----------|
| 1 | 发送音频1 | `[]` | 运行中 |
| 2 | `poll(60秒)` | `[]` | 阻塞 ⏸️ |
| 3 | MuseTalk推理中 | `[]` | 阻塞 ⏸️ |
| 4 | `offer("completed")` | `[completed]` | 阻塞 ⏸️ |
| 5 | `poll()` 返回 | `[]` (自动清空) | 唤醒 ▶️ |
| 6 | 发送音频2 | `[]` | 运行中 |
| 7 | `poll(60秒)` | `[]` | 阻塞 ⏸️ |

---

## 💡 推荐方案:单队列设计

### 方案对比

| 方案 | 队列数量 | 复杂度 | 推荐度 |
|------|---------|-------|--------|
| **方案A: 单队列** | 1个 | 简单 | ⭐⭐⭐⭐⭐ |
| 方案B: 双队列(任务+完成) | 2个 | 中等 | ⭐⭐⭐ |
| 方案C: 三队列(任务+完成+结果) | 3个 | 复杂 | ⭐⭐ |

### 方案A: 单队列实现(推荐)

```java
public class SimpleAudioProcessor {
    // 唯一的阻塞队列,只存完成信号
    private final BlockingQueue<String> completionQueue = 
        new LinkedBlockingQueue<>(1);
    
    private final BlockingQueueMuseTalkClient client;
    
    public void processAudioList(List<FileDto> audioList) {
        for (int i = 0; i < audioList.size(); i++) {
            FileDto audio = audioList.get(i);
            
            try {
                // 1️⃣ 发送音频路径到 MuseTalk
                client.sendAudioRequest(
                    audio.getPath(),      // "/home/audio/segment1.wav"
                    audio.getEmotion()    // "happy"
                );
                logger.info("已发送音频段{}: {}", i, audio.getPath());
                
                // 2️⃣ 阻塞等待完成信号
                String signal = completionQueue.poll(60, TimeUnit.SECONDS);
                
                // 3️⃣ 检查结果
                if ("completed".equals(signal)) {
                    logger.info("音频段{}处理完成", i);
                    // poll() 已自动清空队列,继续下一个
                    
                } else if (signal == null) {
                    logger.error("音频段{}处理超时", i);
                    break;
                    
                } else if ("error".equals(signal)) {
                    logger.error("音频段{}处理失败", i);
                    break;
                }
                
            } catch (Exception e) {
                logger.error("处理音频段{}异常", i, e);
                break;
            }
        }
        
        logger.info("音频列表处理完成");
    }
}
```

### MuseTalk WebSocket 客户端

```java
public class BlockingQueueMuseTalkClient extends WebSocketClient {
    private final Session userSession;
    private final String sessionId;
    private final BlockingQueue<String> completionQueue;
    private final VideoFrameBuffer frameBuffer;
    
    public BlockingQueueMuseTalkClient(
        URI serverUri, 
        Session userSession, 
        String sessionId,
        BlockingQueue<String> completionQueue
    ) {
        super(serverUri);
        this.userSession = userSession;
        this.sessionId = sessionId;
        this.completionQueue = completionQueue;
        this.frameBuffer = new VideoFrameBuffer(userSession, sessionId);
    }
    
    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.info("成功连接到MuseTalk服务");
        frameBuffer.start();
    }
    
    /**
     * 发送音频请求
     */
    public void sendAudioRequest(String audioPath, String emotion) 
        throws IOException {
        
        Map<String, Object> request = Map.of(
            "audio_path", audioPath,
            "avatar", emotion,
            "options", Map.of(
                "jpeg_quality", 50,
                "batch_send", false,
                "verbose", false
            )
        );
        
        String json = objectMapper.writeValueAsString(request);
        logger.info("发送音频请求: {}", json);
        send(json);
    }
    
    /**
     * 接收 JSON 消息(包括完成标识)
     */
    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = JSONObject.parseObject(message);
            String status = json.getString("status");
            
            // 收到完成标识,发送信号到阻塞队列
            if ("completed".equals(status)) {
                logger.info("推理完成,发送完成信号");
                
                boolean offered = completionQueue.offer("completed");
                if (!offered) {
                    logger.warn("完成信号发送失败,队列可能已满");
                }
                return;
            }
            
            // 转发其他消息到前端
            if (userSession.isOpen()) {
                userSession.getAsyncRemote().sendText(message);
            }
            
        } catch (Exception e) {
            logger.error("处理消息异常", e);
            completionQueue.offer("error");
        }
    }
    
    /**
     * 接收视频帧(二进制数据)
     */
    @Override
    public void onMessage(ByteBuffer bytes) {
        // 视频帧直接加入缓冲队列,由独立线程发送到前端
        frameBuffer.addFrame(bytes);
    }
    
    @Override
    public void onError(Exception e) {
        logger.error("WebSocket错误", e);
        completionQueue.offer("error");
        frameBuffer.stop();
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("连接关闭: {}", reason);
        completionQueue.offer("closed");
        frameBuffer.stop();
    }
}
```

---

## 🎯 实现步骤

### 步骤1: 创建阻塞队列

```java
// 容量为1,只存一个完成信号
BlockingQueue<String> completionQueue = new LinkedBlockingQueue<>(1);
```

### 步骤2: 创建 MuseTalk 客户端

```java
URI museTalkUri = new URI("ws://192.168.10.101:8765");
BlockingQueueMuseTalkClient client = new BlockingQueueMuseTalkClient(
    museTalkUri,
    userSession,
    sessionId,
    completionQueue  // 传入阻塞队列
);
client.connect();
```

### 步骤3: 循环处理音频列表

```java
for (FileDto audio : audioList) {
    // 发送音频
    client.sendAudioRequest(audio.getPath(), audio.getEmotion());
    
    // 阻塞等待
    String signal = completionQueue.poll(60, TimeUnit.SECONDS);
    
    // 检查结果
    if (!"completed".equals(signal)) {
        break;
    }
}
```

### 步骤4: WebSocket 回调发送信号

```java
@Override
public void onMessage(String message) {
    if ("completed".equals(status)) {
        completionQueue.offer("completed");  // 唤醒主线程
    }
}
```

---

## 📊 方案对比

### CountDownLatch vs 阻塞队列

| 维度 | CountDownLatch | 阻塞队列 |
|------|---------------|---------|
| **阻塞方式** | `latch.await()` | `queue.poll(timeout)` |
| **唤醒方式** | `latch.countDown()` | `queue.offer(element)` |
| **可重用性** | ❌ 一次性,需要每次new | ✅ 可重复使用 |
| **超时支持** | ✅ `await(timeout)` | ✅ `poll(timeout)` |
| **传递数据** | ❌ 只能传递信号 | ✅ 可以传递结果对象 |
| **代码复杂度** | 较高 | 较低 |
| **状态管理** | 需要手动重置 | 自动管理 |

---

## ✅ 方案优势

1. **代码简洁**: 不需要每次创建 `CountDownLatch`,队列可重复使用
2. **自动管理**: `poll()` 自动清空队列,不需要手动重置状态
3. **统一模型**: 生产者-消费者模式,易于理解和维护
4. **超时控制**: `poll(timeout)` 内置超时机制
5. **错误处理**: 可以传递不同的信号("completed", "error", "timeout")

---

## 🔧 关键 API

### BlockingQueue.poll()

```java
E poll(long timeout, TimeUnit unit) throws InterruptedException
```

**行为**:
- 队列为空: 阻塞等待,直到有元素或超时
- 队列有元素: 立即返回并移除元素
- 超时: 返回 `null`

### BlockingQueue.offer()

```java
boolean offer(E element)
```

**行为**:
- 队列未满: 放入元素,返回 `true`,唤醒等待的 `poll()` 线程
- 队列已满: 返回 `false`(不阻塞)

---

## 📝 注意事项

1. **队列容量**: 建议设置为1,避免积压信号
2. **超时时间**: 根据推理时长设置合理的超时(建议60秒)
3. **错误处理**: 区分超时、错误、正常完成三种情况
4. **资源清理**: 连接关闭时记得停止视频帧缓冲器
5. **线程安全**: `BlockingQueue` 是线程安全的,无需额外同步

---

## 🎬 总结

使用阻塞队列替代 CountDownLatch 实现音频顺序推理控制,核心思路是:

1. **MuseTalk 是生产者**,生产"完成信号"
2. **音频发送端是消费者**,消费"完成信号"
3. **阻塞队列是桥梁**,自动管理阻塞和唤醒
4. **音频数据不进队列**,直接通过 WebSocket 发送路径
5. **视频帧独立传输**,不影响音频顺序控制

这个方案简洁、高效、易维护,是替代 CountDownLatch 的最佳实践。
