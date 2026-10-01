package com.example.diary

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.ShallowEtagHeaderFilter

// GET 응답에 본문 해시로 ETag를 붙인다. 같은 내용이면 본문 없이 304를 돌려준다.
// CORS 허용 헤더도 GET에는 항상 붙인다. @CrossOrigin은 요청에 Origin이 있을 때만 붙이는데,
// Origin 없이 온 요청(curl 등)의 응답이 Cloudflare에 캐시되면 브라우저가 그 응답을 CORS로 거부한다.
@Component
class HttpCacheFilter : ShallowEtagHeaderFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method == "GET") response.setHeader("Access-Control-Allow-Origin", "*")
        super.doFilterInternal(request, response, filterChain)
    }
}
