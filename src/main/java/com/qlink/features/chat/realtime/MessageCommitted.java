package com.qlink.features.chat.realtime;
import java.util.UUID;
public record MessageCommitted(UUID messageId,String type,UUID onlyUser,UUID jobId) {
 public MessageCommitted(UUID messageId) {this(messageId,"MESSAGE_CREATED",null,null);}
 public MessageCommitted(UUID messageId,String type,UUID onlyUser) {this(messageId,type,onlyUser,null);}
}
