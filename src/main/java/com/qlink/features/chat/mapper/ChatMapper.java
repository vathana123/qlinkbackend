package com.qlink.features.chat.mapper;
import com.qlink.features.chat.entity.ChatMessage;
import com.qlink.features.chat.dto.ChatDtos.Message;
import org.mapstruct.Mapper;
@Mapper(componentModel="spring")
public interface ChatMapper { @org.mapstruct.Mapping(target="reply", ignore=true) @org.mapstruct.Mapping(target="hidden", ignore=true) @org.mapstruct.Mapping(target="canDeleteForEveryone", ignore=true) @org.mapstruct.Mapping(target="attachment", ignore=true) @org.mapstruct.Mapping(target="senderDisplayName", ignore=true) Message toMessage(ChatMessage message); }
