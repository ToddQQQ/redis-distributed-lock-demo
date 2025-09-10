import redis.clients.jedis.Jedis;
public class JedisTest {
    public static void main(String[] args) {
        // run redis
        // connect local Redis（inital port 6379）
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            jedis.set("foo", "bar");
            
            String value = jedis.get("foo");
            System.out.println("Redis 中 foo = " + value);
        }
    }
}
