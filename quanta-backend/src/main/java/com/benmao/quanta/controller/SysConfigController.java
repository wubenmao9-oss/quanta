package com.benmao.quanta.controller;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.dto.ConfigRequest;
import com.benmao.quanta.config.dto.ConfigTestResult;
import com.benmao.quanta.config.dto.SysConfigView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 配置层接口：MT5 / LLM / MAIL 三类配置的 CRUD 与连接测试（§15）。 */
@RestController
@RequestMapping("/api/configs")
public class SysConfigController {

    private final ConfigService configService;

    public SysConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /** 只测不存：供前端「先测后存」。 */
    @PostMapping("/test")
    public Result<ConfigTestResult> test(@Valid @RequestBody ConfigRequest request) {
        return Result.ok(configService.test(request));
    }

    /** 新增：测试连接成功才保存。 */
    @PostMapping
    public Result<SysConfigView> save(@Valid @RequestBody ConfigRequest request) {
        return Result.ok(configService.save(request));
    }

    /** 列表：type 可选（MT5/LLM/MAIL），不传返回全部。 */
    @GetMapping
    public Result<List<SysConfigView>> list(@RequestParam(value = "type", required = false) String type) {
        return Result.ok(configService.list(type));
    }

    /** 详情。 */
    @GetMapping("/{id}")
    public Result<SysConfigView> get(@PathVariable("id") Long id) {
        return Result.ok(configService.get(id));
    }

    /** 更新：重新测试通过才写。 */
    @PutMapping("/{id}")
    public Result<SysConfigView> update(@PathVariable("id") Long id,
                                        @Valid @RequestBody ConfigRequest request) {
        return Result.ok(configService.update(id, request));
    }

    /** 删除。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        configService.delete(id);
        return Result.ok();
    }

    /** 激活（同类型互斥）。 */
    @PostMapping("/{id}/activate")
    public Result<SysConfigView> activate(@PathVariable("id") Long id) {
        return Result.ok(configService.activate(id));
    }
}