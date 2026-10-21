package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import ro.bitboy.mcpgp.runtime.mcp.ListPager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Pages the list operations over Streamable HTTP ({@code server.pageSize}, {@link ListPager}). The SDK
 * answers a list in full, as one JSON body or one SSE event; for a list request this filter holds the
 * answer back, cuts it to one page and sends that. Other requests pass through untouched.
 */
final class PagingFilter extends HttpFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int pageSize;

    PagingFilter(int pageSize) {
        this.pageSize = pageSize;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (pageSize <= 0 || !request.getMethod().equals("POST")) {
            chain.doFilter(request, response);
            return;
        }
        InboundFilter.CachedBody body = request instanceof InboundFilter.CachedBody cached ? cached : new InboundFilter.CachedBody(request);
        JsonNode message;
        try {
            message = JSON.readTree(body.bytes());
        } catch (IOException e) {
            chain.doFilter(body, response);
            return;
        }
        String session = request.getHeader("Mcp-Session-Id");
        String key = ListPager.accept(session == null ? "-" : session, message, pageSize);
        if (key == null) {
            chain.doFilter(body, response);
            return;
        }
        Held held = new Held(response);
        try {
            chain.doFilter(body, held);
        } finally {
            ListPager.Pending pending = ListPager.take(key);
            held.release(pending, message.get("id"), pageSize);
        }
    }

    /** A response whose body is kept until {@link #release}. */
    private static final class Held extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final PrintWriter writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));

        Held(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                }
            };
        }

        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        @Override
        public void flushBuffer() {
            // held until release
        }

        @Override
        public void setContentLength(int length) {
            // the length changes with the page
        }

        @Override
        public void setContentLengthLong(long length) {
            // the length changes with the page
        }

        @Override
        public void setHeader(String name, String value) {
            if (!name.equalsIgnoreCase("Content-Length")) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!name.equalsIgnoreCase("Content-Length")) {
                super.addHeader(name, value);
            }
        }

        void release(ListPager.Pending pending, JsonNode id, int pageSize) throws IOException {
            writer.flush();
            byte[] held = buffer.toByteArray();
            byte[] out = pending == null ? held : rewrite(held, pending, id, pageSize);
            if (out.length > 0) {
                getResponse().getOutputStream().write(out);
            }
            getResponse().flushBuffer();
        }

        private byte[] rewrite(byte[] held, ListPager.Pending pending, JsonNode id, int pageSize) {
            String text = new String(held, StandardCharsets.UTF_8);
            String type = getContentType() == null ? "" : getContentType();
            try {
                if (type.startsWith("application/json")) {
                    return JSON.writeValueAsBytes(ListPager.answer(pending, JSON.readTree(text), pageSize));
                }
                StringBuilder rewritten = new StringBuilder();
                for (String line : text.split("\n", -1)) {
                    if (line.startsWith("data:")) {
                        JsonNode data = JSON.readTree(line.substring(5).strip());
                        if (data != null && data.isObject() && data.has("result") && data.path("id").equals(id)) {
                            line = "data: " + JSON.writeValueAsString(ListPager.answer(pending, data, pageSize));
                        }
                    }
                    rewritten.append(line).append('\n');
                }
                rewritten.setLength(rewritten.length() - 1);        // split(-1) gave one empty tail
                return rewritten.toString().getBytes(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return held;
            }
        }
    }
}
