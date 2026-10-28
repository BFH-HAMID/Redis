# RedisJ

[![CI](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml/badge.svg)](https://github.com/BFH-HAMID/Redis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

RedisJ is a RESP-compatible, production-grade in-memory data store written in Java 21.
The goal is to work with the real `redis-cli`, Jedis and Lettuce clients.

> **Status:** Phases 1-2 of 18 done (scaffold, CI, RESP2/RESP3 protocol). The server does not
> accept connections yet; the NIO event loop arrives in Phase 3. See the roadmap below.

## Build

Requires JDK 21 and Maven 3.9+.

```bash
mvn -B verify                       # compile, run unit tests, build target/redisj.jar
java -jar target/redisj.jar --help  # show options
java -jar target/redisj.jar --port 6380 --config redisj.conf.example
```

## Configuration

Configuration is read from a `redisj.conf` file (see `redisj.conf.example`). Command-line
options override file values. Supported directives so far: `port`, `bind`, `requirepass`,
`appendonly`, `maxmemory`, `databases`, `timeout`.

## Roadmap

| Phase | Topic | Status |
|---|---|---|
| 0-1 | Setup, CI | Done |
| 2 | RESP parser and writer | Done |
| 3-4 | NIO event loop, core commands | Planned |
| 5-6 | Data structures, transactions | Planned |
| 7-9 | AOF/RDB, Pub/Sub, Streams, eviction | Planned |
| 10-11 | Replication, cluster, Raft | Planned |
| 12-13 | HLL/Bloom/Geo, vector search | Planned |
| 14-16 | Security, scripting, observability | Planned |
| 17-18 | Testing, benchmarks, Docker, docs, release | Planned |

## License

MIT. See [LICENSE](LICENSE).
