package ro.bitboy.mcpgp.platform.service;

import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.Secrets;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a name and password against the company directory (spec/platform-api.md §3): LDAP (the
 * service account finds the person, then the platform binds as them, so passwords are checked by the
 * directory and never stored here) or Active Directory (a bind as {@code name@domain}). Groups are
 * read by their {@code cn}.
 */
public final class Directory {

    /** Connecting and answering may take this long; a slow directory must not hold sign-ins for good. */
    private static final Map<String, Object> TIMEOUTS = Map.of("com.sun.jndi.ldap.connect.timeout", "5000", "com.sun.jndi.ldap.read.timeout", "10000");

    private final PlatformConfig.Ldap config;
    private final BindAuthenticator bind;
    private final SpringSecurityLdapTemplate groups;
    private final ActiveDirectoryLdapAuthenticationProvider activeDirectory;

    public Directory(PlatformConfig.Ldap config, Map<String, String> env) {
        this.config = config;
        if (config.activeDirectory()) {
            ActiveDirectoryLdapAuthenticationProvider provider = new ActiveDirectoryLdapAuthenticationProvider(config.activeDirectoryDomain(), config.url());
            provider.setContextEnvironmentProperties(TIMEOUTS);
            provider.setConvertSubErrorCodesToExceptions(true);
            this.activeDirectory = provider;
            this.bind = null;
            this.groups = null;
            return;
        }
        DefaultSpringSecurityContextSource source = new DefaultSpringSecurityContextSource(config.url());
        if (config.bindDn() != null) {
            source.setUserDn(config.bindDn());
            source.setPassword(config.bindPassword() == null ? "" : Secrets.resolve(config.bindPassword(), env)
                    .orElseThrow(() -> new IllegalStateException("auth.ldap.bindPassword (" + config.bindPassword() + ") can't be read")));
        }
        if (config.startTls()) {
            source.setAuthenticationStrategy(new DefaultTlsDirContextAuthenticationStrategy());
        }
        source.setBaseEnvironmentProperties(TIMEOUTS);
        source.setPooled(false);
        source.afterPropertiesSet();
        BindAuthenticator authenticator = new BindAuthenticator(source);
        authenticator.setUserSearch(new FilterBasedLdapUserSearch(config.userSearchBase(), config.userSearchFilter(), source));
        this.bind = authenticator;
        this.groups = new SpringSecurityLdapTemplate(source);
        this.activeDirectory = null;
    }

    /** The person, when the name and password are right; empty when they aren't. Directory failures throw. */
    public Optional<SignIns.Person> authenticate(String name, String password) {
        if (name == null || name.isBlank() || password == null || password.isEmpty()) {
            // an empty password would be an anonymous bind, which many directories accept
            return Optional.empty();
        }
        try {
            if (activeDirectory != null) {
                Authentication auth = activeDirectory.authenticate(new UsernamePasswordAuthenticationToken(name, password));
                Set<String> groupNames = new LinkedHashSet<>();
                for (GrantedAuthority a : auth.getAuthorities()) {
                    groupNames.add(a.getAuthority());
                }
                String dn = auth.getPrincipal() instanceof LdapUserDetails details ? details.getDn() : name;
                return Optional.of(new SignIns.Person("ldap", dn, name.contains("@") ? name.substring(0, name.indexOf('@')) : name, null, groupNames));
            }
            DirContextOperations user = bind.authenticate(new UsernamePasswordAuthenticationToken(name, password));
            String dn = user.getNameInNamespace();
            Set<String> groupNames = new LinkedHashSet<>();
            if (config.groupSearchBase() != null) {
                groupNames.addAll(groups.searchForSingleAttributeValues(config.groupSearchBase(), config.groupSearchFilter(),
                        new String[] {dn, name}, "cn"));
            }
            return Optional.of(new SignIns.Person("ldap", dn, name, user.getStringAttribute("mail"), groupNames));
        } catch (InternalAuthenticationServiceException | org.springframework.ldap.NamingException e) {
            throw new IllegalStateException("the directory can't be reached or answered with an error: " + e.getMessage(), e);
        } catch (AuthenticationException e) {
            // wrong password, unknown user, locked or expired account in the directory: all the same answer
            return Optional.empty();
        }
    }
}
