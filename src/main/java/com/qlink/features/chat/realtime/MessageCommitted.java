package com.qlink.features.chat.realtime;
import java.util.UUID;
// Internal transaction event; no entity references or credentials cross the commit boundary.
public record MessageCommitted(UUID messageId,String type,UUID onlyUser) {
 public MessageCommitted(UUID messageId) { this(messageId,"MESSAGE_CREATED",null); }
}
