package com.yoyi.pilates.system.interfaces;
import com.yoyi.pilates.common.result.Result;import com.yoyi.pilates.system.application.SystemConfigService;
import lombok.RequiredArgsConstructor;import org.springframework.core.env.Environment;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequiredArgsConstructor
public class CapabilityController {
 private final com.yoyi.pilates.ledger.StudioLedgerAccess ledger;
 private final Environment env;private final SystemConfigService configs;
 @GetMapping("/api/capabilities")public Result<Map<String,Object>> capabilities(){return Result.ok(Map.of("studioLedger",ledger.enabled(),"bookingMode",configs.get("booking.paymentMode","FREE"),"packageSales",false,"paymentInitiation",false,"refunds",false,"phoneBinding",env.getProperty("yoyi.wx.phone-enabled",Boolean.class,false),"wechatReminder",false,"studioConfigured",configs.get("studio.configured","false").equals("true")));}
}
