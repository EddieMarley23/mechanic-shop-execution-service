package org.project.mechanic_shop.execution.shared.config.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Validates the JWT issued by the OS Service email/password login.
 *
 * <p>This service has no user table, so the token is the only source of identity: the subject is
 * the user's e-mail and the {@code role} claim is the user's role.
 */
@Service
public class TokenVerifier {

	public static final String ROLE_CLAIM = "role";

	@Value("${api.security.token.secret}")
	private String secret;

	@Value("${api.security.token.issuer}")
	private String issuer;

	public record AuthenticatedUser(String email, String role) {}

	public Optional<AuthenticatedUser> verify(String token) {
		try {
			DecodedJWT jwt = JWT.require(Algorithm.HMAC256(secret)).withIssuer(issuer).build().verify(token);

			String email = jwt.getSubject();
			String role = jwt.getClaim(ROLE_CLAIM).asString();
			if (email == null || role == null || role.isBlank()) return Optional.empty();

			return Optional.of(new AuthenticatedUser(email, role));
		} catch (JWTVerificationException exception) {
			return Optional.empty();
		}
	}
}
