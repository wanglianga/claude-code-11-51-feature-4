package com.semantic.platform.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源数据服务：批次导入、覆盖范围查询。
 */
@Service
public class SourceService {

    private final SourceBatchRepository batchRepo;
    private final OrderFactRepository orderRepo;
    private final RefundFactRepository refundRepo;
    private final StoreStatusRepository storeStatusRepo;
    private final HolidayRepository holidayRepo;

    public SourceService(SourceBatchRepository batchRepo, OrderFactRepository orderRepo,
                         RefundFactRepository refundRepo, StoreStatusRepository storeStatusRepo,
                         HolidayRepository holidayRepo) {
        this.batchRepo = batchRepo;
        this.orderRepo = orderRepo;
        this.refundRepo = refundRepo;
        this.storeStatusRepo = storeStatusRepo;
        this.holidayRepo = holidayRepo;
    }

    @Transactional
    public SourceBatch createBatch(String sourceType, String storeId, String status,
                                   LocalDate start, LocalDate end, int recordCount) {
        SourceBatch b = new SourceBatch();
        b.setSourceType(sourceType);
        b.setStoreId(storeId);
        b.setStatus(status);
        b.setPeriodStart(start);
        b.setPeriodEnd(end);
        b.setRecordCount(recordCount);
        return batchRepo.save(b);
    }

    public List<SourceBatch> listBatches() { return batchRepo.findAll(); }

    /** 覆盖矩阵：source x store x date -> 是否覆盖。 */
    public Map<String, Object> coverageView(LocalDate from, LocalDate to) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<SourceBatch> batches = batchRepo.findAll();
        Map<String, Integer> covered = new LinkedHashMap<>();
        for (SourceBatch b : batches) {
            if (!"loaded".equals(b.getStatus())) continue;
            String key = b.getSourceType() + "|" + (b.getStoreId() == null ? "*" : b.getStoreId());
            int days = 0;
            LocalDate s = b.getPeriodStart() == null ? from : b.getPeriodStart();
            LocalDate e = b.getPeriodEnd() == null ? to : b.getPeriodEnd();
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
                if (!d.isBefore(from) && !d.isAfter(to)) days++;
            }
            covered.merge(key, days, Integer::sum);
        }
        out.put("coverage", covered);
        out.put("batches", batches.size());
        return out;
    }
}
