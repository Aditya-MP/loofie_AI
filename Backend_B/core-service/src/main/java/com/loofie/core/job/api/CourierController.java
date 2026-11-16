package com.loofie.core.job.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.CourierService;
import com.loofie.core.job.app.CourierService.CourierOption;
import com.loofie.core.job.domain.Actor;

/** The couriers a brand can pick in the "Shipped" form. Any signed-in user; no tracking patterns exposed. */
@RestController
class CourierController {

    private final CourierService couriers;

    CourierController(CourierService couriers) {
        this.couriers = couriers;
    }

    @GetMapping("/api/core/couriers")
    List<CourierOption> list(Actor actor) {
        return couriers.active();
    }
}
