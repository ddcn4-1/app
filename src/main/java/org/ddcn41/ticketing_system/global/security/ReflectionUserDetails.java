package org.ddcn41.ticketing_system.global.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class ReflectionUserDetails implements UserDetails {

    private final Object user;
    private final String username;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;

    public ReflectionUserDetails(Object user) {
        this.user = Objects.requireNonNull(user, "user cannot be null");
        this.username = resolveUsername(user);
        this.password = resolvePassword(user);
        this.authorities = resolveAuthorities(user);
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired()   { return true; }
    @Override public boolean isAccountNonLocked()    { return true; }
    @Override public boolean isCredentialsNonExpired(){ return true; }
    @Override public boolean isEnabled()             { return true; }

    // ---------- helpers ----------

    private static String resolveUsername(Object u) {
        for (String name : List.of("getUsername", "getEmail", "getLogin", "getLoginId")) {
            String v = invokeString(u, name);
            if (v != null && !v.isBlank()) return v;
        }
        throw new IllegalStateException("Cannot resolve username from user entity.");
    }

    private static String resolvePassword(Object u) {
        for (String name : List.of("getPassword", "getPwd", "getPasswd")) {
            String v = invokeString(u, name);
            if (v != null) return v;
        }
        throw new IllegalStateException("Cannot resolve password from user entity.");
    }

    private static Collection<? extends GrantedAuthority> resolveAuthorities(Object u) {
        Object single = invokeAny(u, List.of("getRole", "getRoleName"));
        if (single != null) {
            return List.of(new SimpleGrantedAuthority(normalizeRole(single)));
        }

        Object rolesObj = invokeAny(u, List.of("getRoles"));
        if (rolesObj instanceof Collection<?> col) {
            return col.stream()
                    .map(ReflectionUserDetails::normalizeRole)
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        }

        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static String normalizeRole(Object r) {
        String s = (r instanceof Enum<?> e) ? e.name() : String.valueOf(r);
        s = s.trim();
        if (!s.startsWith("ROLE_")) s = "ROLE_" + s;
        return s;
    }

    private static String invokeString(Object target, String method) {
        Object v = invokeAny(target, List.of(method));
        return (v == null) ? null : String.valueOf(v);
    }

    private static Object invokeAny(Object target, List<String> methodNames) {
        Class<?> c = target.getClass();
        for (String m : methodNames) {
            try {
                Method method = c.getMethod(m);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke " + m + " on " + c.getName(), e);
            }
        }
        return null;
    }
}
