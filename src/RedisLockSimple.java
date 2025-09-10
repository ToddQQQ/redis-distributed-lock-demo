import redis.clients.jedis.Jedis;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

// Distributed lock: mutual exclusion + blocking retry + watchdog renewal (daemon) + reentrancy (owner:count)
public class RedisLockSimple implements AutoCloseable {
    private final String host;
    private final int port;
    private final Jedis jedis;              // connection for business threads

    // Watchdog
    private ScheduledExecutorService watchdog;
    private Jedis watchdogJedis;            // dedicated connection for watchdog

    // Current lock info (used by watchdog)
    private volatile String currentOwner;
    private volatile String currentKey;
    private volatile long currentTtlMs;

    public RedisLockSimple(String host, int port) {
        this.host = host;
        this.port = port;
        this.jedis = new Jedis(host, port);
    }

    // Try once: return true if success; false if fail (reentrant: same owner increases count)
    public boolean tryLock(String key, String owner, long ttlMs) {
        // value format: "owner:count". Owner can contain colon (e.g. UUID:threadId).
        // Lua regex '^(.*):(%d+)$' splits the last colon as counter.
        String lua =
                "local v = redis.call('get', KEYS[1]) " +
                        "if (not v) then " +
                        "  local ok = redis.call('set', KEYS[1], ARGV[1]..':'..1, 'PX', ARGV[2], 'NX') " +
                        "  if ok then return 1 else return 0 end " +
                        "else " +
                        "  local o, c = string.match(v, '^(.*):(%d+)$') " +
                        "  if not o then o = v; c = 0 end " +
                        "  c = tonumber(c) or 0 " +
                        "  if o == ARGV[1] then " +
                        "    c = c + 1 " +
                        "    redis.call('psetex', KEYS[1], ARGV[2], o..':'..c) " +
                        "    return c " +
                        "  else " +
                        "    return 0 " +
                        "  end " +
                        "end";
        Object r = jedis.eval(lua, 1, key, owner, String.valueOf(ttlMs));
        boolean ok = (r instanceof Long) && ((Long) r) > 0L;
        if (ok) {
            this.currentKey = key;
            this.currentOwner = owner;
            this.currentTtlMs = ttlMs;
        }
        return ok;
    }

    //Blocking lock: retry every retryMs within waitMs. Return true if acquired.
    public boolean lock(String key, String owner, long ttlMs, long waitMs, long retryMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        do {
            if (tryLock(key, owner, ttlMs)) {
                startWatchdog(); // start watchdog only if lock is acquired
                return true;
            }
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    // Unlock: decrement reentrant counter; only DEL when counter == 0
    public boolean unlock(String key, String owner) {
        stopWatchdog(); // stop renewal first to avoid race with DEL
        String lua =
                "local v = redis.call('get', KEYS[1]) " +
                        "if (not v) then return 0 end " +
                        "local o, c = string.match(v, '^(.*):(%d+)$') " +
                        "if not o then o = v; c = 1 end " +
                        "c = tonumber(c) or 1 " +
                        "if o ~= ARGV[1] then return 0 end " +
                        "c = c - 1 " +
                        "if c > 0 then " +
                        "  redis.call('psetex', KEYS[1], ARGV[2], o..':'..c) " +
                        "  return c " +
                        "else " +
                        "  return redis.call('del', KEYS[1]) " +
                        "end";
        Object res = jedis.eval(lua, 1, key, owner, String.valueOf(currentTtlMs));
        boolean ok;
        if (res instanceof Long) {
            long v = (Long) res;      // v==1 means fully released (DEL), v>1 means still has reentrancy layers
            ok = v >= 1;
        } else {
            ok = false;
        }
        if (ok && (res instanceof Long) && ((Long) res) == 1L) {
            // fully released, clear local state
            this.currentKey = null;
            this.currentOwner = null;
            this.currentTtlMs = 0L;
        }
        return ok;
    }

    // Start watchdog: renew every ttl/3 ms; renew only if still owned by me (checked with Lua) //
    private void startWatchdog() {
        if (watchdog != null) return; // already started
        long period = Math.max(500, currentTtlMs / 3);
        watchdog = Executors.newSingleThreadScheduledExecutor(new DaemonTF("lock-watchdog"));
        watchdogJedis = new Jedis(host, port); // dedicated connection

        watchdog.scheduleAtFixedRate(() -> {
            try {
                String key = currentKey;
                String owner = currentOwner;
                long ttlMs = currentTtlMs;
                if (key == null || owner == null || ttlMs <= 0) return;

                // Renew only if owner matches (ignore counter part)
                String lua =
                        "local v = redis.call('get', KEYS[1]) " +
                                "if (not v) then return 0 end " +
                                "local o, _ = string.match(v, '^(.*):(%d+)$') " +
                                "if not o then o = v end " +
                                "if o == ARGV[1] then " +
                                "  return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                                "else return 0 end";
                watchdogJedis.eval(lua, 1, key, owner, String.valueOf(ttlMs));
            } catch (Throwable ignored) {
                // ignore any exception to keep watchdog alive
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    // Stop watchdog
    private void stopWatchdog() {
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        if (watchdogJedis != null) {
            try { watchdogJedis.close(); } catch (Exception ignore) {}
            watchdogJedis = null;
        }
    }

    // Helper: generate owner (UUID:threadId)
    public static String newOwner() {
        return UUID.randomUUID() + ":" + Thread.currentThread().getId();
    }

    @Override
    public void close() {
        stopWatchdog();
        if (Objects.nonNull(jedis)) jedis.close();
    }

    // Daemon thread factory: exit with business threads
    static class DaemonTF implements ThreadFactory {
        private final String prefix;
        DaemonTF(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        }
    }
}