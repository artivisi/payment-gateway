package com.artivisi.paymentgateway.adapter.cimb;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.transport.http.MessageDispatcherServlet;

/**
 * Spring-WS wiring for the CIMB SOAP adapter. Registers a MessageDispatcherServlet on a dedicated
 * path so it coexists with the REST DispatcherServlet. {@code @Endpoint} beans are auto-detected;
 * JAXB-annotated payloads are bound by the default payload processors (no marshaller bean needed).
 */
@EnableWs
@Configuration
public class CimbSoapConfig {

    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> cimbMessageDispatcherServlet(
            ApplicationContext applicationContext) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(applicationContext);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/cimb/*");
    }
}
