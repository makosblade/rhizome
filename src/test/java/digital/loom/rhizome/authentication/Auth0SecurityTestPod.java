package digital.loom.rhizome.authentication;

import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@EnableGlobalMethodSecurity(
    prePostEnabled = true )
@EnableWebSecurity(
    debug = false )
public class Auth0SecurityTestPod extends Auth0SecurityPod {
    @Override
    protected void authorizeRequests( HttpSecurity http ) throws Exception {
        http.authorizeRequests()
                .antMatchers( "/api/unsecured/**" ).authenticated()
                .antMatchers( "/api/secured/foo" ).hasAnyAuthority( "foo", "FOO" )
                .antMatchers( "/api/secured/admin" ).hasAnyAuthority( "admin", "ADMIN" )
                .antMatchers( "/api/secured/user" ).hasAnyAuthority( "user", "USER" );
    }
}
