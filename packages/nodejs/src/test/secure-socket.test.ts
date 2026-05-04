import test from 'node:test';
import assert from 'node:assert/strict';
import { SecureServerSocket, SecureSocket } from '../secure-socket';

test('SecureSocket transfers every byte value transparently', async () => {
  const server = await SecureServerSocket.listen(0);
  try {
    const payload = Buffer.from(Array.from({ length: 256 }, (_, index) => index));
    const received = (async () => {
      const accepted = await server.accept();
      try {
        return await accepted.receiveBytes();
      } finally {
        accepted.close();
      }
    })();

    const client = await SecureSocket.connect('127.0.0.1', server.getLocalPort());
    try {
      await client.sendBytes(payload);
    } finally {
      client.close();
    }

    assert.deepEqual(await received, payload);
  } finally {
    server.close();
  }
});

test('SecureSocket keeps EOF distinct from business byte 0x04', async () => {
  const server = await SecureServerSocket.listen(0);
  try {
    const received = (async () => {
      const accepted = await server.accept();
      try {
        return [await accepted.receiveBytes(), await accepted.receiveBytes()];
      } finally {
        accepted.close();
      }
    })();

    const client = await SecureSocket.connect('127.0.0.1', server.getLocalPort());
    try {
      await client.sendBytes(Buffer.from([0x04]));
      await client.sendBytes(null);
    } finally {
      client.close();
    }

    const [businessPayload, eof] = await received;
    assert.deepEqual(businessPayload, Buffer.from([0x04]));
    assert.equal(eof, null);
  } finally {
    server.close();
  }
});
