package com.qlink.features.push;
import java.util.*;
import org.springframework.data.jpa.repository.*;
public interface PushDeviceRepository extends JpaRepository<PushDevice,UUID> {
 Optional<PushDevice> findByInstallationId(UUID installation);
 Optional<PushDevice> findByTokenHash(String hash);
 List<PushDevice> findByUserId(UUID user);
 void deleteBySessionId(UUID session);
}
