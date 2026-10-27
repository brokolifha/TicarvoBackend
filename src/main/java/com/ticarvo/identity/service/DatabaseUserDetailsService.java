package com.ticarvo.identity.service;
import com.ticarvo.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.core.userdetails.*;
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
 private final UserRepository users;
 public DatabaseUserDetailsService(UserRepository users) { this.users=users; }
 @Override public UserDetails loadUserByUsername(String username) {
  return users.findByEmail(IdentityService.normalizeEmail(username))
   // Spring checks credentialsExpired only after the password matched, so the unverified state never leaks without it.
   .map(user -> User.withUsername(user.getEmail()).password(user.getPasswordHash())
    // An MFA user only logs in after the code was checked, so the session carries proof of the second factor (ADR-008).
    .authorities(user.isMfaEnabled() ? new String[]{"ROLE_USER", AccountModeration.MFA_AUTHORITY} : new String[]{"ROLE_USER"})
    // SecurityConfiguration also moves the disabled check after the password, so a suspension never leaks without it.
    .disabled(user.isSuspended())
    .credentialsExpired(!user.isEmailVerified()).build())
   .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
 }
}
