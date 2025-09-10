import redis.clients.jedis.Jedis;
public class JedisTest {
    public static void main(String[] args) {
        // 连接本地 Redis（默认端口 6379）
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            // 写入一个键值对
            jedis.set("foo", "bar");

            // 取出来看看
            String value = jedis.get("foo");
            System.out.println("Redis 中 foo = " + value);
        }
    }
}
