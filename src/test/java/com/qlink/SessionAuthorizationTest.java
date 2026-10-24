package com.qlink;

import com.qlink.features.auth.repository.AuthSessionRepository;
import com.qlink.features.auth.service.SessionAuthorization;
import com.qlink.common.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAuthorizationTest {
    private final AuthSessionRepository sessions=mock(AuthSessionRepository.class);
    private final SessionAuthorization authorization=new SessionAuthorization(sessions);
    private final UUID user=UUID.randomUUID(), session=UUID.randomUUID();
    private Jwt token(Instant expiry) {
        return Jwt.withTokenValue("test").header("alg","RS256").subject(user.toString())
                .claim("sid",session.toString()).issuedAt(Instant.now().minusSeconds(120)).expiresAt(expiry).build();
    }
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void cachesOnlyWithinOneRequestButExplicitRechecksObserveRevocation() {
        var jwt=token(Instant.now().plusSeconds(60));
        when(sessions.countActive(eq(session),eq(user),any())).thenReturn(1L,0L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(authorization.actor(jwt)).isEqualTo(user);
        assertThat(authorization.actor(jwt)).isEqualTo(user);
        verify(sessions,times(1)).countActive(eq(session),eq(user),any());
        assertThatThrownBy(() -> authorization.recheck(jwt)).isInstanceOf(ApiException.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThatThrownBy(() -> authorization.actor(jwt)).isInstanceOf(ApiException.class);
    }

    @Test void socketOperationsNeverReuseAnEarlierAuthorization() {
        var jwt=token(Instant.now().plusSeconds(60));
        when(sessions.countActive(eq(session),eq(user),any())).thenReturn(1L,0L);
        assertThat(authorization.actor(jwt)).isEqualTo(user);
        assertThatThrownBy(() -> authorization.actor(jwt)).isInstanceOf(ApiException.class);
    }

    @Test void expiredAccessNeverReachesDatabase() {
        assertThatThrownBy(() -> authorization.actor(token(Instant.now().minusSeconds(1))))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(sessions);
    }
}
