package com.loofie.core.ops.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.CourierService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Courier;

/**
 * F6: ops edits the courier list (name, kind, tracking-link pattern with {tracking}, on/off, order).
 * HTTP mapping only: permissions and rules are in {@link CourierService}.
 */
@RestController
@RequestMapping("/api/core/ops/couriers")
class OpsCourierController {

    record CourierRequest(@NotBlank @Size(max = 64) String name, @NotNull Courier.Kind kind,
                          @Size(max = 300) String trackingUrlPattern, boolean active, int sortOrder) {
    }

    private final CourierService couriers;

    OpsCourierController(CourierService couriers) {
        this.couriers = couriers;
    }

    @GetMapping
    List<Courier> list(Actor actor) {
        return couriers.all(actor);
    }

    @PutMapping("/{code}")
    ResponseEntity<Void> upsert(Actor actor, @PathVariable String code, @Valid @RequestBody CourierRequest body) {
        boolean created = couriers.upsert(actor, new Courier(code, body.name(), body.kind(), body.trackingUrlPattern(),
                body.active(), body.sortOrder()));
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }
}
