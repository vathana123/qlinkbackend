package com.qlink.features.chat.realtime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
 private final SocketSecurity security; private final List<String> origins;
 private final org.springframework.messaging.MessageChannel outbound;
 private final tools.jackson.databind.json.JsonMapper json;
 public WebSocketConfig(SocketSecurity security,@Value("${qlink.websocket.allowed-origins}") List<String> origins,
  @org.springframework.beans.factory.annotation.Qualifier("clientOutboundChannel") @Lazy org.springframework.messaging.MessageChannel outbound,tools.jackson.databind.json.JsonMapper json) {
  if(origins.isEmpty() || origins.stream().anyMatch(o -> o.contains("*"))) throw new IllegalArgumentException("Explicit WebSocket origins required");
  this.security=security;this.origins=origins;this.outbound=outbound;this.json=json;
 }
 @Bean public ThreadPoolTaskScheduler chatHeartbeatScheduler() {
  var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("chat-heartbeat-");return scheduler;
 }
 @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
  registry.setApplicationDestinationPrefixes("/app");registry.setUserDestinationPrefix("/user");
  registry.enableSimpleBroker("/queue").setTaskScheduler(chatHeartbeatScheduler()).setHeartbeatValue(new long[]{10000,10000});
  registry.setPreservePublishOrder(true);
  // The simple broker has no general receipt support. Acknowledge only a processed user
  // subscription, allowing the client to start REST catch-up after delivery is active.
  registry.configureBrokerChannel().interceptors(new org.springframework.messaging.support.ChannelInterceptor() {
   @Override public void postSend(Message<?> message,org.springframework.messaging.MessageChannel channel,boolean sent) {
    var h=StompHeaderAccessor.wrap(message);
    if(sent && h.getMessageType()==org.springframework.messaging.simp.SimpMessageType.SUBSCRIBE && h.getReceipt()!=null) {
     var ack=StompHeaderAccessor.create(StompCommand.RECEIPT);
     ack.setSessionId(h.getSessionId());ack.setReceiptId(h.getReceipt());
     outbound.send(MessageBuilder.createMessage(new byte[0],ack.getMessageHeaders()));
    }
   }
  });
 }
 @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
  registry.addEndpoint("/ws").setAllowedOrigins(origins.toArray(String[]::new));
  registry.setErrorHandler(new StompSubProtocolErrorHandler() {
   @Override public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> client,Throwable ex) {
    var headers=StompHeaderAccessor.create(StompCommand.ERROR);headers.setMessage("Connection not authorized");
    return MessageBuilder.createMessage("Connection not authorized".getBytes(StandardCharsets.UTF_8),headers.getMessageHeaders());
   }
  });
 }
 @Override public boolean configureMessageConverters(List<org.springframework.messaging.converter.MessageConverter> converters) {
  converters.add(new org.springframework.messaging.converter.JacksonJsonMessageConverter(json));
  return true;
 }
 @Override public void configureClientInboundChannel(ChannelRegistration registration) {
  // Authenticate before the deny-by-default authorization policy in the same interceptor.
  registration.interceptors(security);
 }
 @Override public void configureClientOutboundChannel(ChannelRegistration registration) {registration.interceptors(security.outbound());}
 @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
  registration.setMessageSizeLimit(16*1024).setSendBufferSizeLimit(256*1024).setSendTimeLimit(10000).setTimeToFirstMessage(10000);
 }
}
