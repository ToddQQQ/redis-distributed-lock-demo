public class LockDemoMain {
    public static void main(String[] args) throws Exception {
        String lockKey = "demo:lock";
        long ttlMs   = 3000;   // lock expiration 3s
        long waitMs  = 10000;  // wait up to 10s to acquire lock
        long retryMs = 200;    // retry interval 200ms

        Runnable task = () -> {
            String owner = RedisLockSimple.newOwner();  // keep same owner per thread
            try (RedisLockSimple lock = new RedisLockSimple("localhost", 6379)) {
                boolean ok = lock.lock(lockKey, owner, ttlMs, waitMs, retryMs);
                if (!ok) {
                    System.out.println(Thread.currentThread().getName() + " failed to acquire lock (timeout)");
                    return;
                }
                System.out.println(Thread.currentThread().getName() + " acquired lock");

                // Demonstrate reentrancy: same thread tries again (should be true)
                boolean re = lock.tryLock(lockKey, owner, ttlMs);
                System.out.println(Thread.currentThread().getName() + " reentered lock: " + re);

                try {
                    // simulate business longer than TTL, watchdog will keep it alive
                    Thread.sleep(8000);
                    System.out.println(Thread.currentThread().getName() + " finished business");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    // must unlock twice (matching reentrancy count)
                    boolean released1 = lock.unlock(lockKey, owner);
                    System.out.println(Thread.currentThread().getName() + " first unlock result: " + released1);
                    boolean released2 = lock.unlock(lockKey, owner);
                    System.out.println(Thread.currentThread().getName() + " second unlock result: " + released2);
                }
            }
        };

        Thread t1 = new Thread(task, "Thread-A");
        Thread t2 = new Thread(task, "Thread-B");
        t1.start();
        Thread.sleep(300); // let A acquire first
        t2.start();

        t1.join();
        t2.join();
        System.out.println("Demo finished");
    }
}