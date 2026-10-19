package com.asea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Main entry point for the ASEA Control Plane.
 *
 * <p>This is a single modular monolith. Division B (AI agents) and Division C
 * (execution/sandbox) provide implementations of the contracts exposed by this
 * module; they are not embedded here.</p>
 *
 * <p>{@link EnableJpaAuditing} activates Spring Data's {@code @CreatedDate} and
 * {@code @LastModifiedDate} processing so that audit timestamps on entities
 * are automatically populated on save. Without this, NOT NULL constraint
 * violations occur on {@code created_at} and {@code updated_at} columns.</p>
 */
@SpringBootApplication
@EnableJpaAuditing
public class AseaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AseaApplication.class, args);
    }
}
