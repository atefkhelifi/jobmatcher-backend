package ai.jobmatcher.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(email.toLowerCase())
                .map(AppUserDetails::of)
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }
}
