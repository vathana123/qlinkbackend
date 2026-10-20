package com.qlink.features.chat.realtime;
import com.qlink.features.chat.dto.ChatDtos.*;
import com.qlink.features.chat.enums.ChatRoomType;
import java.util.UUID;
public record ChatEvent(UUID eventId,String type,UUID roomId,ChatRoomType roomType,Message message,Room room) {}
