package com.qlink.features.push;
public interface PushGateway {
 boolean enabled();
 void send(PushDevice device,java.util.UUID room,java.util.UUID message,java.util.UUID recipient,java.util.UUID notification) throws Exception;
 class InvalidToken extends Exception {}
}
