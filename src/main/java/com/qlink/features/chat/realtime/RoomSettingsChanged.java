package com.qlink.features.chat.realtime;
import java.util.UUID;
public record RoomSettingsChanged(UUID userId, UUID roomId) {}
