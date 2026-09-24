package dev.queuelive;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AbuseFilter extends OncePerRequestFilter {
    private final Map<String, Bucket> buckets = new HashMap<>();
    private record Bucket(long window, int requests) {}
    private synchronized boolean allowed(String ip, boolean login) {
        long window = System.currentTimeMillis() / 60_000;
        buckets.entrySet().removeIf(entry -> entry.getValue().window() < window);
        String key = ip + (login ? ":login" : ":api");
        Bucket old = buckets.getOrDefault(key, new Bucket(window, 0));
        if (buckets.size() >= 10_000 && !buckets.containsKey(key)) return false;
        buckets.put(key, new Bucket(window, old.requests() + 1));
        return old.requests() < (login ? 20 : 600);
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("Cache-Control", "no-store");
        if (req.getContentLengthLong() > 4096) { SecurityConfig.error(res, 413, "Request too large"); return; }
        if (!allowed(req.getRemoteAddr(), req.getRequestURI().equals("/api/login"))) {
            res.setHeader("Retry-After", "60"); SecurityConfig.error(res, 429, "Too many requests; wait a minute"); return;
        }
        chain.doFilter(req, res);
    }
}
