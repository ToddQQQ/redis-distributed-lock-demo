
# 分布式锁

## 如何实现一个 Redis 分布式锁？

### 1. 加锁
- 使用命令：`SET key value NX PX ttl`
  - `NX`：保证互斥（只有 key 不存在时才能设置成功）。
  - `PX`：设置过期时间，避免线程挂了锁无法释放。
- Redis 是单线程执行 → 这条命令天然原子。

---

### 2. 解锁
- 必须保证只有持有锁的线程才能解锁 → 两步操作需要保证原子性。
- 使用 Lua 脚本来保证原子操作：
```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
  return redis.call("del", KEYS[1])
else
  return 0
end
```



### 3. 锁过期与看门狗
- 如果线程挂了，锁会在过期时间后自动释放（避免死锁）。
- 问题：如果业务没执行完就过期，锁会被别人抢走。
- 解决办法：看门狗机制（Redisson 的实现）。
  - 加锁成功后，后台定时任务（守护线程）会定期执行 `PEXPIRE` 给锁续期。
  - 业务线程正常 → 锁持续有效。
  - 业务线程挂掉 → JVM 终止守护线程，锁最终过期释放。

---

### 4. 可重入锁
- 同一线程可能多次进入临界区，需要支持可重入。
- 本地锁（如 `ReentrantLock`）都有计数器：重入 +1，释放 -1。
- Redis 分布式锁也需要类似机制。
- Redisson 的实现：
  - 使用 Redis Hash 结构：
    - **Key**：锁名
    - **Field**：线程唯一标识（UUID + ThreadId，避免集群冲突）
    - **Value**：重入计数
  - 重入时计数 +1，释放时计数 -1，直到 0 才真正释放。

---

### 5. 阻塞锁
- 如果没抢到锁：
  - 简单实现：自旋（循环重试）。
  - Redisson 的实现：
    - 没抢到锁的线程订阅一个频道并阻塞。
    - 持锁线程释放锁时，发布消息唤醒订阅者。
    - 唤醒的线程再尝试加锁。
    - 可以设置超时时间，避免无限等待。

---

### 6. 主从复制问题
- Redis 主从复制是异步的：
  - 客户端写入主节点成功，但主节点宕机，还没同步给从节点。
  - 从节点被提升为主 → 锁数据丢失。
  - 其他客户端可能同时拿到锁 → 违背互斥性。
- 解决办法：在多个主节点上一致加锁，保证安全。

---

### 7. RedLock（红锁）
- Redis 作者提出的分布式锁算法。
- 在 N 个独立 Redis 主节点上加锁，必须在超过半数的节点成功才算成功。
- 超过一定时间还没加锁成功就放弃。
- 优点：
  - 保证多主架构下的互斥性。
- 缺点：
  - 不同节点时钟可能不一致。
  - JVM GC 暂停可能导致看门狗续期失败。
  - 多主节点运维复杂。
- 因此：红锁并不常用。大多数公司：
  - 要么用 Redis 基础命令封装简单锁；
  - 要么直接使用 Redisson 框架。











# Distributed Lock

## How to implement a Redis-based distributed lock?

---

### 1. Acquire Lock
- Use command: `SET key value NX PX ttl`
  - `NX`: ensures mutual exclusion (only set if the key does not exist).
  - `PX`: sets an expiration time to avoid deadlocks if the client crashes.
- Redis executes commands in a single thread → this command is atomic by nature.

---

### 2. Release Lock
- Only the lock owner should be able to release it → this requires atomicity.
- Solution: use a Lua script to check ownership and delete in one step:

```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
  return redis.call("del", KEYS[1])
else
  return 0
end
```

### 3. Expiration and Watchdog
- If the client crashes, the lock will expire automatically (avoids deadlock).
- Problem: if the business logic takes longer than TTL, the lock may expire too early and another client can acquire it.
- Solution: **Watchdog mechanism** (used by Redisson).
  - After acquiring the lock, a background **daemon thread** periodically calls `PEXPIRE` to extend the TTL.
  - If the business thread is alive → the lock remains valid.
  - If the business thread crashes → JVM stops the daemon thread, the lock will eventually expire and be released.

---

### 4. Reentrant Lock
- A thread might need to acquire the same lock multiple times (e.g., method A calls method B, both require the same lock).
- Local locks (e.g., `ReentrantLock`) solve this with a counter: +1 on reentry, -1 on release.
- Distributed lock needs a similar mechanism.
- Redisson’s approach:
  - Use a **Redis Hash**:
    - **Key**: lock name
    - **Field**: thread identifier (UUID + ThreadId, to avoid collisions in clusters)
    - **Value**: reentry count
  - On reentry: counter +1
  - On release: counter -1
  - Lock is fully released when counter = 0.

---

### 5. Blocking Lock
- If a thread fails to acquire the lock:
  - **Simple implementation**: spin and retry (busy-wait).
  - **Redisson’s implementation**:
    - Threads that fail to acquire subscribe to a channel and block.
    - When the lock is released, the holder publishes a message.
    - Subscribers wake up and retry acquiring the lock.
    - A timeout can be set to avoid infinite waiting.

---

### 6. Master-Slave Replication Issue
- Redis replication is **asynchronous**:
  - A client sets the lock on the master.
  - The master crashes before replicating to the slave.
  - The slave gets promoted to master → lock data is lost.
  - Another client may acquire the lock, violating mutual exclusion.
- Solution: acquire the lock **consistently across multiple masters**.

---

### 7. RedLock
- Proposed by Redis’ creator (**Antirez**).
- Deploy multiple **independent Redis masters**.
- Acquire lock on a **majority** of masters to succeed.
- If lock attempts take too long → give up.
- **Advantages**:
  - Provides stronger guarantees in multi-master setups.
- **Drawbacks**:
  - Clock drift across nodes may break safety.
  - JVM GC pauses may cause the watchdog to miss renewals, leading to premature expiration.
  - Operating multiple independent masters is complex and costly.
- **Conclusion**: RedLock is **not commonly used** in practice.  
  Most companies:
  - Either build simple distributed locks with Redis primitives.
  - Or use frameworks like **Redisson**.

---


1. Acquire: `SET key value NX PX ttl` (atomic).  
2. Release: check ownership + delete with Lua (atomic).  
3. Expiration → Watchdog renews TTL (daemon thread).  
4. Reentrant lock → Redis Hash counter (Redisson).  
5. Blocking → Spin or Pub/Sub notify (Redisson).  
6. Master-slave replication risk → need multi-master strategy.  
7. RedLock → majority success, but issues with clock drift and complexity → rarely used.  
