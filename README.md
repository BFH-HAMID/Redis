# RedisJ

[![CI](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml/badge.svg)](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

RedisJ is a RESP-compatible, production-grade in-memory data store written in Java 21.
The goal is to work with the real `redis-cli`, Jedis and Lettuce clients.

> **Status:** Phases 1-4 committed; Phases 5 (lists, hashes, sets), 6 (transactions), 7 (AOF and
> snapshots), 8 (pub/sub), 9 (streams and eviction), 10 (minimal replication) and 11 (static cluster)
> written. CI is failing and nothing in Phases 2-11 has been verified by a build yet. See the roadmap below.

## Build

Requires JDK 21 and Maven 3.9+.

```bash
mvn -B verify                       # compile, run unit tests, build target/redisj.jar
java -jar target/redisj.jar --help  # show options
java -jar target/redisj.jar --port 6380 --config redisj.conf.example
```

## Run the server

```bash
java -jar target/redisj.jar --port 6379 &
printf 'PING\r\n' | nc 127.0.0.1 6379     # +PONG
```

The server runs one event loop for accept and command execution. Set `io-threads N` to move
socket reads, parsing and writes onto N I/O loops. Commands still run on the single execution loop.
`maxclients`, `timeout` (idle seconds) and `SIGINT`/`SIGTERM` graceful shutdown are supported.

## Supported commands (Phase 4)

- Connection: `PING`, `ECHO`, `QUIT`, `SELECT`, `AUTH`, `HELLO`, `CLIENT SETNAME|GETNAME|ID|LIST|SETINFO`
- Keys: `DEL`, `UNLINK`, `EXISTS`, `TYPE`, `RENAME`, `KEYS`, `SCAN`, `DBSIZE`, `FLUSHDB`, `FLUSHALL`
- Expiry: `EXPIRE`, `PEXPIRE`, `EXPIREAT`, `PEXPIREAT` (with `NX|XX|GT|LT`), `TTL`, `PTTL`, `PERSIST`
- Strings: `GET`, `SET` (`NX|XX|GET|EX|PX|EXAT|PXAT|KEEPTTL`), `GETSET`, `MGET`, `MSET`, `APPEND`,
  `STRLEN`, `GETRANGE`, `SETRANGE`
- Counters: `INCR`, `DECR`, `INCRBY`, `DECRBY`, `INCRBYFLOAT`
- Lists: `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE`, `LINDEX`
- Hashes: `HSET`, `HGET`, `HDEL`, `HEXISTS`, `HLEN`, `HGETALL`, `HKEYS`, `HVALS`
- Sets: `SADD`, `SREM`, `SISMEMBER`, `SMEMBERS`, `SCARD`
- Transactions: `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`
- Persistence: `SAVE` (see below)
- Cluster: `CLUSTER KEYSLOT|MYID|INFO` (static slot map, see below)
- Replication: `SYNC`, `REPLICAOF host port`, `REPLICAOF NO ONE` (full sync only)
- Streams: `XADD`, `XLEN`, `XRANGE`, `XREVRANGE`, `XDEL`, `XTRIM`, `XREAD` (non-blocking)
- Pub/sub: `SUBSCRIBE`, `UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE`, `PUBLISH`, `PUBSUB CHANNELS|NUMSUB|NUMPAT`

Expired keys are removed on access and by an active cycle that runs about every 100 ms. When
`requirepass` is set, every command except `AUTH` and `HELLO` needs authentication first.

## Configuration

Configuration is read from a `redisj.conf` file (see `redisj.conf.example`). Command-line
options override file values. Supported directives so far: `port`, `bind`, `requirepass`,
`appendonly`, `maxmemory`, `databases`, `timeout`, `io-threads`, `maxclients`.

## Persistence

- `appendonly yes`: every successful write is appended to `appendonly.aof` in the working directory and
  replayed at startup. The file is fsynced about once per second. Keys with a TTL are recorded with an
  absolute expiry time, so a restart does not extend them.
- `appendonly no` (default): data is kept in memory and written only by `SAVE`, to `dump.redisj`, and
  loaded at startup.
- `SAVE` writes the dataset to a temporary file and renames it into place. In AOF mode it rewrites
  `appendonly.aof` from the current dataset instead.
- The snapshot format is a RESP command stream that rebuilds the dataset. It is **not** Redis's binary
  RDB format, so RDB files from a real Redis server cannot be loaded. `BGSAVE` is not implemented.

## Streams and memory limits

- Streams keep entries ordered by ID. `XADD` accepts `*`, `ms-*`, or an explicit `ms-seq`, and
  `MAXLEN` trims the oldest entries. The last ID is kept even after deletes, so IDs keep increasing.
- `XREAD` is non-blocking. `BLOCK` returns an error for now.
- `maxmemory` limits the estimated size of the dataset. `maxmemory-policy` is `noeviction` (writes
  are refused with `OOM`) or `allkeys-random` (random keys are removed). `DEL`, `UNLINK`, `FLUSHDB`
  and `FLUSHALL` are always allowed.
- Memory is estimated from payload sizes plus rough per-object overhead. It is not the JVM's real
  heap use. Each write scans every key while a limit is set, so the cost grows with the dataset.
- Evictions are logged to the append-only file as `DEL`, so replay removes the same keys.
- Not implemented: `allkeys-lru`, `volatile-*` policies, and blocking `XREAD`.

## Replication (minimal)

- `REPLICAOF host port` copies the master's dataset once, using `SYNC`, and makes this server
  read-only. `REPLICAOF NO ONE` makes it writable again.
- The dataset is sent as a RedisJ snapshot, not Redis's RDB format.
- Only a full sync. Writes made on the master after the copy are **not** sent to the replica.
- The transfer blocks the replica's execution loop until it finishes or times out (5 s to connect,
  30 s to read).
- Not supported: `REPLICAOF` while `appendonly` is on, master `requirepass` (the replica sends no
  AUTH), `PSYNC`, replication offsets, and automatic reconnection.

## Cluster (static)

- Enable with `cluster-enabled yes`, `cluster-myself <id>`, and one `cluster-node <id> <host:port>
  <from>-<to>` line per node. Every node gets the same list. The ranges must cover all 16384 slots
  exactly once.
- Keys are hashed with CRC16 modulo 16384, as in Redis Cluster, and `{hash tags}` are honoured.
- A command whose keys belong to another node returns `MOVED <slot> <host:port>`. Keys in one
  command that hash to different slots return `CROSSSLOT`.
- Nodes do not talk to each other. Slots cannot move at runtime. There is no Raft, gossip, failover,
  or `ASK` redirect. Commands with keys that are only found by parsing (such as `XREAD`) are not
  routed.

## Pub/sub

- A connection that holds at least one subscription can run only `SUBSCRIBE`, `UNSUBSCRIBE`,
  `PSUBSCRIBE`, `PUNSUBSCRIBE`, `PING`, and `QUIT`. Other commands get an error, as in Redis.
- Messages are delivered as RESP2 arrays: `["message", channel, payload]`, or
  `["pmessage", pattern, channel, payload]` for pattern subscribers.
- `PUBLISH` returns the number of deliveries. Messages are not written to the append-only file.
- Known differences from Redis: RESP3 push frames are not used, so RESP3 clients also receive arrays.
  Subscriptions are not kept across restarts, and `RESET` is not implemented.

## Roadmap

| Phase | Topic | Status |
|---|---|---|
| 0-1 | Setup, CI | Done |
| 2 | RESP parser and writer | Done |
| 3 | NIO event loop, I/O threads, graceful shutdown | Done |
| 4 | Command registry, core commands, expiry | Done |
| 5 | Lists, hashes, sets | Written, not yet verified |
| 6 | Transactions (MULTI/EXEC, WATCH) | Written, not yet verified |
| 7 | AOF and snapshots | Written, not yet verified |
| 8 | Pub/Sub | Written, not yet verified |
| 9 | Streams and eviction | Written, not yet verified |
| 10 | Replication (minimal: full sync, read-only replica) | Written, not yet verified |
| 11 | Static cluster: slot hashing, MOVED/CROSSSLOT (no Raft, no gossip) | Written, not yet verified |
| 12 | Raft, gossip, failover, resharding | Planned |
| 13 | HLL/Bloom/Geo, vector search | Planned |
| 14-16 | Security, scripting, observability | Planned |
| 17-18 | Testing, benchmarks, Docker, docs, release | Planned |

## License

MIT. See [LICENSE](LICENSE).
