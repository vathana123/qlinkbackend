package com.qlink.storage;
import com.qlink.features.storage.service.VoiceFiles;
import com.qlink.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.*;
import static org.assertj.core.api.Assertions.*;
public class VoiceFilesTest {
    public static byte[] wav(int millis) {
        int size=millis*32;
        var b=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36+size).put("WAVEfmt ".getBytes()).putInt(16);
        b.putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes()).putInt(size); return b.array();
    }
    @Test void validatesActualDurationAndBounds() throws Exception {
        for(int duration:new int[]{300,1000,120000}) assertThat(VoiceFiles.wavDuration(new ByteArrayInputStream(wav(duration)))).isEqualTo(duration);
        for(int duration:new int[]{100,120001,130000}) assertThatThrownBy(() -> VoiceFiles.wavDuration(new ByteArrayInputStream(wav(duration)))).isInstanceOf(ApiException.class);
    }
    @Test void rejectsMalformedOrNonPcmAudio() {
        byte[] bad=wav(1000); bad[20]=3;
        assertThatThrownBy(() -> VoiceFiles.wavDuration(new ByteArrayInputStream(bad))).isInstanceOf(ApiException.class);
        byte[] truncated=java.util.Arrays.copyOf(wav(1000),100);
        assertThatThrownBy(() -> VoiceFiles.wavDuration(new ByteArrayInputStream(truncated))).isInstanceOf(ApiException.class);
    }
}
