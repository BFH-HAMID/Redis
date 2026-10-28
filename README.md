# RedisJ

[![CI](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml/badge.svg)](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

RedisJ is a RESP-compatible, production-grade in-memory data store written in Java 21.
The goal is to work with the real `redis-cli`, Jedis and Lettuce clients.

> **Status:** Phases 1-4 of 18 done (scaffold, CI, RESP2/RESP3 protocol, NIO server, command
> registry with string, key and expiry commands). See the roadmap below.

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

Expired keys are removed on access and by an active cycle that runs about every 100 ms. When
`requirepass` is set, every command except `AUTH` and `HELLO` needs authentication first.

## Configuration

Configuration is read from a `redisj.conf` file (see `redisj.conf.example`). Command-line
options override file values. Supported directives so far: `port`, `bind`, `requirepass`,
`appendonly`, `maxmemory`, `databases`, `timeout`, `io-threads`, `maxclients`.

## Roadmap

| Phase | Topic | Status |
|---|---|---|
| 0-1 | Setup, CI | Done |
| 2 | RESP parser and writer | Done |
| 3 | NIO event loop, I/O threads, graceful shutdown | Done |
| 4 | Command registry, core commands, expiry | Done |
| 5-6 | Data structures, transactions | Planned |
| 7-9 | AOF/RDB, Pub/Sub, Streams, eviction | Planned |
| 10-11 | Replication, cluster, Raft | Planned |
| 12-13 | HLL/Bloom/Geo, vector search | Planned |
| 14-16 | Security, scripting, observability | Planned |
| 17-18 | Testing, benchmarks, Docker, docs, release | Planned |

## License

MIT. See [LICENSE](LICENSE).
