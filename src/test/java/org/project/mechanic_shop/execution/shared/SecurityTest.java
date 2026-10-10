package org.project.mechanic_shop.execution.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.execution.shared.config.observability.CorrelationIdFilter;
import org.project.mechanic_shop.execution.shared.config.security.JwtAuthenticationFilter;
import org.project.mechanic_shop.execution.shared.config.security.SecurityConfigurations;
import org.project.mechanic_shop.execution.shared.config.security.TokenVerifier;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

class SecurityTest {

	private static final String SECRET = "test-secret";
	private static final String ISSUER = "mechanic-shop-api";

	private TokenVerifier verifier;
	private JwtAuthenticationFilter filter;

	@BeforeEach
	void setup() {
		verifier = new TokenVerifier();
		ReflectionTestUtils.setField(verifier, "secret", SECRET);
		ReflectionTestUtils.setField(verifier, "issuer", ISSUER);
		filter = new JwtAuthenticationFilter(verifier);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private String token(String secret, String issuer, String role, Instant expiresAt) {
		var builder = JWT.create().withIssuer(issuer).withSubject("mecanico@teste.com").withExpiresAt(expiresAt);
		if (role != null) builder.withClaim("role", role);
		return builder.sign(Algorithm.HMAC256(secret));
	}

	private String validToken() {
		return token(SECRET, ISSUER, "MECHANIC", Instant.now().plusSeconds(600));
	}

	@Test
	void shouldReadEmailAndRoleFromAValidToken() {
		assertThat(verifier.verify(validToken())).hasValue(new TokenVerifier.AuthenticatedUser("mecanico@teste.com", "MECHANIC"));
	}

	@Test
	void shouldRefuseTokensThatCannotBeTrusted() {
		Instant future = Instant.now().plusSeconds(600);

		assertThat(verifier.verify("garbage")).isEmpty();
		assertThat(verifier.verify(token("other-secret", ISSUER, "ADMIN", future))).isEmpty();
		assertThat(verifier.verify(token(SECRET, "someone-else", "ADMIN", future))).isEmpty();
		assertThat(verifier.verify(token(SECRET, ISSUER, "ADMIN", Instant.now().minusSeconds(60)))).isEmpty();
	}

	@Test
	void shouldRefuseATokenWithoutRoleBecauseThisServiceHasNoUserTable() {
		Instant future = Instant.now().plusSeconds(600);

		assertThat(verifier.verify(token(SECRET, ISSUER, null, future))).isEmpty();
		assertThat(verifier.verify(token(SECRET, ISSUER, " ", future))).isEmpty();
	}

	@Test
	void shouldAuthenticateTheRequestFromTheBearerToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + validToken());

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		var authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication.getName()).isEqualTo("mecanico@teste.com");
		assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_MECHANIC");
	}

	@Test
	void shouldLeaveTheRequestAnonymousWithoutAValidToken() throws Exception {
		MockHttpServletRequest withoutHeader = new MockHttpServletRequest();
		MockHttpServletRequest withBadToken = new MockHttpServletRequest();
		withBadToken.addHeader("Authorization", "Bearer nope");
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(withoutHeader, new MockHttpServletResponse(), chain);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(chain.getRequest()).isNotNull();

		filter.doFilter(withBadToken, new MockHttpServletResponse(), new MockFilterChain());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void shouldHaveNoLocalUsers() {
		var userDetailsService = new SecurityConfigurations(filter).userDetailsService();

		assertThatThrownBy(() -> userDetailsService.loadUserByUsername("admin")).isInstanceOf(
			UsernameNotFoundException.class
		);
	}

	@Test
	void shouldPropagateTheInboundCorrelationId() throws Exception {
		CorrelationIdFilter correlationFilter = new CorrelationIdFilter();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr-123");
		MockHttpServletResponse response = new MockHttpServletResponse();
		String[] seenInsideTheRequest = new String[1];

		correlationFilter.doFilter(request, response, (req, res) ->
			seenInsideTheRequest[0] = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)
		);

		assertThat(seenInsideTheRequest[0]).isEqualTo("corr-123");
		assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo("corr-123");
		assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
	}

	@Test
	void shouldFallBackToTheRequestIdOrGenerateACorrelationId() throws Exception {
		CorrelationIdFilter correlationFilter = new CorrelationIdFilter();
		MockHttpServletRequest withRequestId = new MockHttpServletRequest();
		withRequestId.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, " ");
		withRequestId.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "req-9");
		MockHttpServletResponse first = new MockHttpServletResponse();
		MockHttpServletResponse second = new MockHttpServletResponse();

		correlationFilter.doFilter(withRequestId, first, new MockFilterChain());
		correlationFilter.doFilter(new MockHttpServletRequest(), second, new MockFilterChain());

		assertThat(first.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo("req-9");
		assertThat(second.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).hasSize(36);
	}
}
