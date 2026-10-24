package com.qlink.features.storage.service;



import com.qlink.features.chat.entity.ChatMessage;

import com.qlink.features.chat.enums.*;

import com.qlink.features.chat.repository.*;

import com.qlink.features.storage.entity.StoredFile;

import com.qlink.features.storage.enums.*;

import com.qlink.features.storage.repository.StoredFileRepository;

import org.springframework.stereotype.Service;

import java.io.*;

import java.nio.*;

import java.nio.charset.StandardCharsets;

import java.util.*;



/** Attachment checks run inside the message transaction, holding the file lock. */

@Service

public class VoiceFiles {

    public static final int MAX_BYTES=4_000_000;

    private final com.qlink.features.user.repository.UserRepository users;

    private final StoredFileRepository files;

    private final FileStorageProvider provider;

    private final ChatMessageRepository messages;

    private final ChatRoomRepository rooms;

    private final ChatMemberRepository members;

    public VoiceFiles(StoredFileRepository files, FileStorageProvider provider, ChatMessageRepository messages,

                      ChatRoomRepository rooms, ChatMemberRepository members, com.qlink.features.user.repository.UserRepository users) {
        this.users=users;

        this.files=files; this.provider=provider; this.messages=messages; this.rooms=rooms; this.members=members;

    }

    public void attach(UUID actor, ChatMessage message, UUID id, long claimedDuration) {

        var file=files.lockIncludingDeleted(id).orElseThrow(StorageErrors::missing);

        if (!actor.equals(file.getUploadedBy()) || file.getDeletedAt()!=null || file.getStatus()!=StoredFileStatus.AVAILABLE)

            throw StorageErrors.missing();

        if (messages.includingDeletedByFileId(id).isPresent()) throw StorageErrors.invalid("File already attached");

        if (file.getSizeBytes()>MAX_BYTES) throw StorageErrors.tooLarge();

        if (file.getCategory()!=FileCategory.AUDIO || !Set.of("audio/vnd.wave","audio/x-wav","audio/wav").contains(file.getContentType()))

            throw StorageErrors.unsupported();

        long duration;

        try (var input=provider.load(file.getStorageKey())) { duration=wavDuration(input); }

        catch(IOException ex) { throw StorageErrors.unavailable(); }

        if(Math.abs(duration-claimedDuration)>1000) throw StorageErrors.invalid("Recording duration does not match audio");

        file.setVoiceRecording(true);

        message.setType(MessageType.VOICE); message.setFileId(id); message.setDurationMs(duration);

        message.setAudioContentType(file.getContentType()); message.setAudioSizeBytes(file.getSizeBytes());

    }

    /** Reuse the stored object and its authoritative metadata for ordinary attachments. */
    public void attachFile(UUID actor, ChatMessage message, UUID id, MessageType type) {
        var file=files.lockIncludingDeleted(id).orElseThrow(StorageErrors::missing);
        if (!actor.equals(file.getUploadedBy()) || file.getDeletedAt()!=null || file.getStatus()!=StoredFileStatus.AVAILABLE)
            throw StorageErrors.missing();
        if (users.avatarReferences(id)>0 || rooms.avatarReferences(id)>0 || file.isVoiceRecording() || messages.includingDeletedByFileId(id).isPresent())
            throw StorageErrors.invalid("File already attached or reserved for voice");
        if ((type==MessageType.IMAGE)!=(file.getCategory()==FileCategory.IMAGE))
            throw StorageErrors.invalid("Message type does not match file");
        message.setType(type); message.setFileId(id);
    }

    public com.qlink.features.chat.dto.ChatDtos.Attachment describe(ChatMessage message) {
        if (message.getType()!=MessageType.IMAGE && message.getType()!=MessageType.FILE) return null;
        var file=files.findById(message.getFileId()).orElseThrow(StorageErrors::missing);
        return new com.qlink.features.chat.dto.ChatDtos.Attachment(file.getOriginalName(),file.getContentType(),file.getSizeBytes());
    }

    /** Strict bounded PCM WAV validation; duration is derived from data, never client metadata. */

    public static long wavDuration(InputStream input) throws IOException {

        byte[] bytes=input.readNBytes(MAX_BYTES+1);

        if(bytes.length>MAX_BYTES) throw StorageErrors.tooLarge();

        if(bytes.length<44) throw StorageErrors.unsupported();

        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        if(!tag(bytes,0).equals("RIFF") || !tag(bytes,8).equals("WAVE") || Integer.toUnsignedLong(b.getInt(4))+8!=bytes.length)

            throw StorageErrors.unsupported();

        int rate=0, align=0, data=0; boolean format=false;

        for(int offset=12;offset+8<=bytes.length;) {

            String name=tag(bytes,offset); long size=Integer.toUnsignedLong(b.getInt(offset+4));

            int start=offset+8;

            if(size>bytes.length-start) throw StorageErrors.unsupported();

            if(name.equals("fmt ")) {

                if(format || size<16 || b.getShort(start)!=1) throw StorageErrors.unsupported();

                int channels=Short.toUnsignedInt(b.getShort(start+2));

                rate=b.getInt(start+4); align=Short.toUnsignedInt(b.getShort(start+12));

                if(channels!=1 || rate!=16000 || b.getShort(start+14)!=16 || align!=2 || b.getInt(start+8)!=rate*align)

                    throw StorageErrors.unsupported();

                format=true;

            }

            if(name.equals("data")) { if(data!=0) throw StorageErrors.unsupported(); data=(int)size; }

            offset=start+(int)size+((int)size&1);

        }

        if(!format || data==0 || data%align!=0) throw StorageErrors.unsupported();

        long duration=1000L*data/(rate*align);

        if(duration<300 || duration>120000) throw StorageErrors.invalid("Recording must last 0.3–120 seconds");

        return duration;

    }

    private static String tag(byte[] bytes,int offset) { return new String(bytes,offset,4,StandardCharsets.US_ASCII); }

    public void requireRead(UUID actor, StoredFile file) {

        var groupAvatar=rooms.includingDeletedByAvatarFileId(file.getId());
        if(groupAvatar.isPresent()) {
            var room=groupAvatar.get();
            if(room.getDeletedAt()!=null || members.findByRoomId(room.getId()).stream().noneMatch(m -> actor.equals(m.getUserId())))
                throw StorageErrors.missing();
            return;
        }

        if (users.avatarReferences(file.getId())>0) return; // Avatars are visible to authenticated users.
        var attached=messages.includingDeletedByFileId(file.getId());

        if(attached.isEmpty()) {

            if(!actor.equals(file.getUploadedBy())) throw StorageErrors.missing();

            return;

        }

        var message=attached.get();

        if(message.getDeletedAt()!=null) throw StorageErrors.missing();

        var room=rooms.findById(message.getRoomId()).orElseThrow(StorageErrors::missing);

        var member=members.findByRoomId(room.getId()).stream().filter(m -> actor.equals(m.getUserId()))

            .findFirst().orElseThrow(StorageErrors::missing);

        if(room.getType()!=ChatRoomType.PERSONAL && room.getMessageHistoryVisibility()==MessageHistoryVisibility.VIEW_AFTER_JOIN

           && message.getCreatedAt().isBefore(member.getJoinedAt())) throw StorageErrors.missing();

    }

    public void requireUnattached(UUID id) {

        if(users.avatarReferences(id)>0 || rooms.avatarReferences(id)>0 || messages.includingDeletedByFileId(id).isPresent()) throw StorageErrors.invalid("Attached files cannot be deleted independently");

    }

}

