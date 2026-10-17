package com.phoenixscout.web;

import com.phoenixscout.sim.LiveSimService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 模拟仓实时盈亏接口：http://127.0.0.1:8100/api/sim */
@RestController
public class SimController {

    private final LiveSimService live;

    public SimController(LiveSimService live) {
        this.live = live;
    }

    @GetMapping("/api/sim")
    public Map<String, Object> sim() {
        return live.snapshot();
    }
}
