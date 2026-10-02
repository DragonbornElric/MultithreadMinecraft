"""Minimal RCON client for the MultithreadMC lab server (no dependencies).

    python lab/rcon.py "time set day" "weather clear"
"""
from __future__ import annotations

import socket
import struct
import sys


class Rcon:
    def __init__(self, host: str = "127.0.0.1", port: int = 25575, password: str = "mtmclab"):
        self.sock = socket.create_connection((host, port), timeout=120)
        self._id = 0
        if self._send(3, password) == -1:
            raise PermissionError("RCON authentication failed")

    def _send(self, kind: int, body: str) -> int:
        self._id += 1
        data = struct.pack("<ii", self._id, kind) + body.encode() + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(data)) + data)
        request_id, _ = self._read()
        return request_id

    def _read(self) -> tuple[int, str]:
        length = struct.unpack("<i", self._recv(4))[0]
        payload = self._recv(length)
        request_id, _kind = struct.unpack("<ii", payload[:8])
        return request_id, payload[8:-2].decode(errors="replace")

    def _recv(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            buf += chunk
        return buf

    def cmd(self, command: str) -> str:
        """Run a command. The server splits responses into 4096-character packets, so keep
        reading while a packet is exactly that long (a short timeout covers a response that is
        an exact multiple). Only one request is ever in flight: the server's RCON reader handles
        just the first request of each network read, so pipelining would lose requests."""
        self._id += 1
        data = struct.pack("<ii", self._id, 2) + command.encode() + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(data)) + data)
        _, body = self._read()
        parts = [body]
        while len(body) == 4096:
            old = self.sock.gettimeout()
            self.sock.settimeout(1.0)
            try:
                _, body = self._read()
                parts.append(body)
            except (socket.timeout, TimeoutError):
                break
            finally:
                self.sock.settimeout(old)
        return "".join(parts)

    def close(self) -> None:
        self.sock.close()


if __name__ == "__main__":
    r = Rcon()
    for c in sys.argv[1:]:
        print(f"> {c}\n{r.cmd(c)}")
    r.close()
