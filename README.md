# RedisJ

[![CI](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml/badge.svg)](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

RedisJ is a RESP-compatible, production-grade in-memory data store written in Java 21.
The goal is to work with the real `redis-cli`, Jedis and Lettuce clients.

> **Status:** Phases 1-4 committed; Phases 5 (lists, hashes, sets), 6 (transactions) and 7 (AOF and
> snapshots) written. CI is failing and nothing in Phases 2-7 has been verified by a build yet. See the roadmap below.

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
| 8-9 | Pub/Sub, Streams, eviction | Planned |
| 10-11 | Replication, cluster, Raft | Planned |
| 12-13 | HLL/Bloom/Geo, vector search | Planned |
| 14-16 | Security, scripting, observability | Planned |
| 17-18 | Testing, benchmarks, Docker, docs, release | Planned |

## License

MIT. See [LICENSE](LICENSE).
