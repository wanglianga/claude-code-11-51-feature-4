#!/bin/bash
# 经营指标语义层与自助分析平台 — 端到端验证脚本
# 覆盖：登录、指标校验（币种/粒度/环路）、运行、覆盖范围、迟到退款、报表更正，
#       客单金额分布（编译/精确vs近似/误差界/样本守恒/降级/局部重建/发布冻结/去重决议）
set -e
BASE="${BASE:-http://localhost:8080}"
PASS=0
FAIL=0

ok() { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

# 就绪等待：服务可登录且样例数据播种完成（指标 ≥ 7），最多 90 秒
echo "=== 0. 等待服务就绪 ==="
READY=0
for i in $(seq 1 30); do
  T=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null || true)
  if [ -n "$T" ]; then
    C=$(curl -s "$BASE/api/metrics" -H "X-Token: $T" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || true)
    if [ "${C:-0}" -ge 7 ]; then READY=1; break; fi
  fi
  sleep 3
done
[ "$READY" = "1" ] && ok "服务就绪（样例数据已播种）" || { bad "服务未就绪"; exit 1; }

echo "=== 1. 登录 ==="
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null)
if [ -n "$TOKEN" ]; then ok "登录成功"; else bad "登录失败"; exit 1; fi
AUTH="X-Token: $TOKEN"

echo "=== 2. 指标列表 ==="
COUNT=$(curl -s "$BASE/api/metrics" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
[ "$COUNT" -ge 7 ] && ok "已发布指标 $COUNT 个" || bad "指标数量不足: $COUNT"

echo "=== 3. 币种校验（CNY 未过滤 + USD 应报错） ==="
RES=$(curl -s -X POST "$BASE/api/metrics/validate" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"t1","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]},\"right\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"USD\"}],\"by\":[\"store_id\"]}}"}')
echo "$RES" | grep -q "币种" && ok "币种不匹配被拦截" || bad "币种校验未生效"

echo "=== 4. 粒度校验（日 × 月 应报错） ==="
# 先创建月粒度指标（若不存在）
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"monthly_sales","name":"月销售额","grain":"month","definition":"{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"CNY\"}],\"by\":[\"store_id\"]}"}' > /dev/null 2>&1
curl -s -X POST "$BASE/api/metrics/monthly_sales/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":1}' > /dev/null 2>&1
RES=$(curl -s -X POST "$BASE/api/metrics/validate" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"t2","grain":"day","definition":"{\"op\":\"arith\",\"operator\":\"mul\",\"left\":{\"op\":\"ref\",\"metric\":\"net_sales\"},\"right\":{\"op\":\"ref\",\"metric\":\"monthly_sales\"}}"}')
echo "$RES" | grep -q "粒度" && ok "粒度不匹配被拦截" || bad "粒度校验未生效"

echo "=== 5. 运行净销售额（2月，验证缺批次覆盖） ==="
VID=$(curl -s "$BASE/api/metrics/net_sales/published" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
RUN=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"net_sales\",\"metricVersionId\":$VID,\"from\":\"2026-02-01\",\"to\":\"2026-02-28\",\"grain\":\"month\"}")
RUNID=$(echo "$RUN" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
S003=$(curl -s "$BASE/api/runs/$RUNID/cells" -H "$AUTH" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    if 'S003' in str(c.get('dimsJson','')):
        print(c['cellStatus']); break")
[ "$S003" = "partial" ] && ok "S003 缺批次标记为 partial（未当成零销售）" || bad "S003 覆盖标记异常: $S003"

echo "=== 6. 跨营业日订单归属 ==="
RUN9=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"net_sales\",\"metricVersionId\":$VID,\"from\":\"2026-01-09\",\"to\":\"2026-01-09\",\"grain\":\"day\"}")
RUN9ID=$(echo "$RUN9" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
VAL=$(curl -s "$BASE/api/runs/$RUN9ID/cells" -H "$AUTH" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    if 'S001' in str(c.get('dimsJson','')):
        print(c['valueNum']); break")
[ "$VAL" = "800.0" ] || [ "$VAL" = "800" ] && ok "跨营业日订单 O002 计入 1/9（净销售额 800）" || bad "跨营业日归属异常: $VAL"

echo "=== 7. 迟到退款影响计算 ==="
IMPACT=$(curl -s -X POST "$BASE/api/replay/late-refund" -H "$AUTH" -H "Content-Type: application/json" -d '{"refundId":"R-VERIFY","orderId":"O001","storeId":"S001","businessDate":"2026-01-10","amount":"50.00"}')
echo "$IMPACT" | grep -q "candidates" && ok "迟到退款生成运行候选" || bad "迟到退款影响计算失败"

echo "=== 8. 同店增长队列 ==="
SVID=$(curl -s "$BASE/api/metrics/same_store_growth/published" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
if [ -n "$SVID" ]; then
  RUNS=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"same_store_growth\",\"metricVersionId\":$SVID,\"from\":\"2026-01-01\",\"to\":\"2026-03-31\",\"grain\":\"month\"}")
  RUNSID=$(echo "$RUNS" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
  EXCLUDED=$(curl -s "$BASE/api/runs/$RUNSID/cells" -H "$AUTH" | python3 -c "
import sys,json
n=0
for c in json.load(sys.stdin):
    if c['cellStatus']=='excluded': n+=1
print(n)")
  [ "$EXCLUDED" -gt 0 ] && ok "同店队列排除说明生效（$EXCLUDED 个排除单元格）" || bad "同店队列排除未生效"
fi

echo "=== 9. 引用环路（并发发布组合重检） ==="
# 创建两个相互引用的指标
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_a","name":"环A","grain":"day","definition":"{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"CNY\"}],\"by\":[\"store_id\"]}"}' > /dev/null
curl -s -X POST "$BASE/api/metrics/vcyc_a/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":1}' > /dev/null
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_b","name":"环B","grain":"day","definition":"{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"refunds\",\"field\":\"amount\"},\"by\":[\"store_id\"]}"}' > /dev/null
curl -s -X POST "$BASE/api/metrics/vcyc_b/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":1}' > /dev/null
# 创建相互引用的新版本
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_a","name":"环A","grain":"day","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"ref\",\"metric\":\"vcyc_b\"},\"right\":{\"op\":\"const\",\"value\":1}}"}' > /dev/null
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_b","name":"环B","grain":"day","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"ref\",\"metric\":\"vcyc_a\"},\"right\":{\"op\":\"const\",\"value\":1}}"}' > /dev/null
# 先发布 vcyc_a v2（应成功）
R1=$(curl -s -X POST "$BASE/api/metrics/vcyc_a/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":2}')
# 再发布 vcyc_b v2（应失败，组合成环）
R2=$(curl -s -X POST "$BASE/api/metrics/vcyc_b/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":2}')
echo "$R2" | grep -q "环路" && ok "并发发布组合环路被拦截" || bad "环路检测未生效: $R2"

echo "=== 10. 比率上卷（分子分母重新汇总） ==="
AVID=$(curl -s "$BASE/api/metrics/aov/published" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
if [ -n "$AVID" ]; then
  RUNA=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"aov\",\"metricVersionId\":$AVID,\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"grain\":\"month\"}")
  RUNAID=$(echo "$RUNA" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
  HASNUMDEN=$(curl -s "$BASE/api/runs/$RUNAID/cells" -H "$AUTH" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    if c['valueNum'] is not None and c['valueDen'] is not None and c['valueDen']>0:
        print('yes'); break")
  [ "$HASNUMDEN" = "yes" ] && ok "比率单元格含分子分母（上卷重新汇总）" || bad "比率分子分母缺失"
fi

# ============================================================
# 客单金额分布查询（精确 vs 近似对照、误差界、样本守恒、局部重建、发布冻结）
# ============================================================

echo "=== 11. 订单金额编译（稳定订单键 → 单一观察值） ==="
COMPILE=$(curl -s -X POST "$BASE/api/dist/compile" -H "$AUTH" -H "Content-Type: application/json" -d '{}')
echo "$COMPILE" | python3 -c "
import sys,json
c=json.load(sys.stdin)
assert c['compiledOrders']>=113, f\"编译订单数异常: {c['compiledOrders']}\"
assert c['pending']>=2 and c['excluded']>=3, f\"状态分类异常: {c}\"
print('ok')" > /dev/null 2>&1 && ok "全量编译：113+ 订单键各得一个观察值（待核/排除单列）" || bad "编译或状态分类异常: $COMPILE"
PENDING=$(curl -s "$BASE/api/dist/orders?state=pending" -H "$AUTH")
echo "$PENDING" | grep -q "O-CONFLICT" && echo "$PENDING" | grep -q "O-PAYX" \
  && ok "待核单列：回执冲突(O-CONFLICT)、支付不一致(O-PAYX)" || bad "待核订单缺失: $PENDING"
EXCLUDED=$(curl -s "$BASE/api/dist/orders?state=excluded" -H "$AUTH")
echo "$EXCLUDED" | grep -q "O-VOID" && echo "$EXCLUDED" | grep -q "O-UNPAID" && echo "$EXCLUDED" | grep -q "O004" \
  && ok "撤销/未支付/外币不混入已完成交易" || bad "排除订单缺失: $EXCLUDED"

echo "=== 12. 精确路线 vs 独立复算（相同样本，样本守恒） ==="
curl -s "$BASE/api/dist/orders?storeId=S001&bizWeek=2026-W06" -H "$AUTH" > /tmp/dist_w06.json
curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-02-02","to":"2026-02-08","storeIds":["S001"],"route":"exact"}' > /tmp/dist_q_exact.json
python3 - <<'EOF' && ok "精确路线与独立复算一致（中位数/P90/高额占比），订单总体=样本+待核+排除" || bad "精确路线与独立复算不一致"
import json
obs=json.load(open('/tmp/dist_w06.json'))
cell=json.load(open('/tmp/dist_q_exact.json'))['cells'][0]
# 独立复算：从观察值明细重算
nets=sorted(float(o['netAmount']) for o in obs if o['state']=='completed')
def quantile(s,q):
    h=q*(len(s)-1); i=int(h); f=h-i
    return s[i]+f*(s[min(i+1,len(s)-1)]-s[i])
assert len(nets)==cell['validSamples'], f"样本数不一致 {len(nets)} vs {cell['validSamples']}"
assert cell['population']==cell['validSamples']+cell['pendingCount']+cell['excludedCount'], "样本不守恒"
assert abs(quantile(nets,0.5)-float(cell['median']['value']))<1e-6, "中位数不一致"
assert abs(quantile(nets,0.9)-float(cell['p90']['value']))<1e-6, "P90 不一致"
high=sum(1 for v in nets if v>=1000)/len(nets)
assert abs(high-float(cell['highRatio']['value']))<1e-6, "高额占比不一致"
print('ok')
EOF

echo "=== 13. 近似路线 vs 精确路线（同一订单集合对照，误差界有效） ==="
curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-02-02","to":"2026-02-08","storeIds":["S001"],"route":"approx"}' > /tmp/dist_q_approx.json
python3 - <<'EOF' && ok "近似分位数真实排名落在报告的误差界内，且与精确值一致" || bad "近似路线误差界失效"
import json
obs=json.load(open('/tmp/dist_w06.json'))
exact=json.load(open('/tmp/dist_q_exact.json'))['cells'][0]
approx=json.load(open('/tmp/dist_q_approx.json'))['cells'][0]
assert approx['route']=='approx' and not approx['degraded'], "应走近似路线"
nets=sorted(float(o['netAmount']) for o in obs if o['state']=='completed')
n=len(nets)
for m,q in [('median',0.5),('p90',0.9)]:
    est=approx[m]; ev=float(exact[m]['value']); av=float(est['value'])
    # 近似值的真实排名（<=av 的样本数）必须落在 [rankLo,rankHi] 内
    rank=sum(1 for v in nets if v<=av)
    assert est['rankLo']-1e-9<=rank<=est['rankHi']+1e-9, f"{m} 真实排名 {rank} 超出界 [{est['rankLo']},{est['rankHi']}]"
    # 排名与 q 的偏差不超过误差界 + 离散化容差（type-7 约定与重复值各引入 ≤1 样本偏差）
    assert abs(rank/n-q)<=float(est['rankErrorBound'])+1.5/n+0.001, f"{m} 排名偏差超误差界"
    assert abs(av-ev)<=1.0, f"{m} 近似 {av} 与精确 {ev} 偏差过大"
print('ok')
EOF

echo "=== 14. 业态维度下钻（渠道分格样本守恒） ==="
curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-02-02","to":"2026-02-08","storeIds":["S001"],"dims":["channel"],"route":"exact","minSample":2}' > /tmp/dist_q_dim.json
python3 - <<'EOF' && ok "渠道分格样本合计=全量样本；团购渠道高额占比=100%" || bad "维度下钻样本不守恒"
import json
exact=json.load(open('/tmp/dist_q_exact.json'))['cells'][0]
cells=json.load(open('/tmp/dist_q_dim.json'))['cells']
total=sum(c['validSamples'] for c in cells)
assert total==exact['validSamples'], f"分格样本 {total} != 全量 {exact['validSamples']}"
grp=[c for c in cells if c['dims'].get('channel')=='groupon'][0]
assert abs(float(grp['highRatio']['value'])-1.0)<1e-9, "团购渠道高额占比应为 100%"
assert float(grp['p90']['value'])>5000, "团购渠道 P90 应为大额"
print('ok')
EOF

echo "=== 15. 小样本降级（标出不可用，不以均值替代） ==="
SMALL=$(curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-02-09","to":"2026-02-15","storeIds":["S002"]}')
echo "$SMALL" | python3 -c "
import sys,json
c=json.load(sys.stdin)['cells'][0]
assert c['status']=='unavailable_small_sample', f\"应标出不可用: {c['status']}\"
assert c['median'] is None and c['p90'] is None, '不得返回均值替代'
print('ok')" > /dev/null 2>&1 && ok "有效样本 4 < 10 → 分位数不可用（未静默返回均值）" || bad "小样本降级失效: $SMALL"

echo "=== 16. 不支持的过滤维度（不假称精确，降级并说明） ==="
DEG=$(curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-02-02","to":"2026-02-08","storeIds":["S001"],"route":"approx","filters":{"amountMin":"50"}}')
echo "$DEG" | python3 -c "
import sys,json
c=json.load(sys.stdin)['cells'][0]
assert c['route']=='exact' and c['degraded'], '应降级为精确重算'
assert c['degradeReason'], '必须说明降级原因'
print('ok')" > /dev/null 2>&1 && ok "金额过滤无近似摘要 → 降级精确重算并显式标记" || bad "不支持维度降级失效: $DEG"

echo "=== 17. 迟到部分退款：局部重建 + 发布冻结 ==="
Q7='{"from":"2026-02-09","to":"2026-02-15","storeIds":["S001"],"route":"exact"}'
BEFORE=$(curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" -d "$Q7")
PUB=$(curl -s -X POST "$BASE/api/dist/publications" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"name\":\"verify-w07\",\"params\":$Q7}")
PUBID=$(echo "$PUB" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
curl -s -X POST "$BASE/api/dist/revisions/late-refund" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"refundId":"RF-VERIFY-DIST","orderId":"O-GRP2","storeId":"S001","businessDate":"2026-02-20","amount":"11500.00"}' > /tmp/dist_refund.json
AFTER=$(curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" -d "$Q7")
PUBNOW=$(curl -s "$BASE/api/dist/publications/$PUBID" -H "$AUTH")
printf '%s\n' "$BEFORE" "$AFTER" "$PUBNOW" | python3 -c "
import sys,json
lines=[json.loads(l) for l in sys.stdin if l.strip().startswith('{')]
before,after,pub=lines[0],lines[1],lines[2]
h0=float(before['cells'][0]['highRatio']['value'])
h1=float(after['cells'][0]['highRatio']['value'])
hp=float(pub['result']['cells'][0]['highRatio']['value'])
# 退款已应用（首次或幂等重放）：当前高额占比必为 0（O-GRP2 净额 500 < 1000）
assert h1==0.0, f'退款后高额占比应为 0: {h1}'
# 发布快照保持发布时的样本与数值
assert hp==h0, f'快照应冻结发布时值 {h0}，实际 {hp}'
print('ok')" > /dev/null 2>&1 && ok "迟到退款只重建受影响集合；快照冻结发布时数值" || bad "迟到退款/发布冻结失效"
# 局部重建：W07 摘要版本 > W06 摘要版本（仅受影响格重建）
curl -s "$BASE/api/dist/summaries" -H "$AUTH" | python3 -c "
import sys,json
ss=json.load(sys.stdin)
w06=max(s['compileId'] for s in ss if s['storeId']=='S001' and s['bizWeek']=='2026-W06')
w07=max(s['compileId'] for s in ss if s['storeId']=='S001' and s['bizWeek']=='2026-W07')
assert w07>w06, f'W07 摘要应重建（{w07}）而 W06 保持（{w06}）'
print('ok')" > /dev/null 2>&1 && ok "仅受影响（门店×周）摘要版本重建，其它保持原版本" || bad "摘要局部重建失效"

echo "=== 18. 订单去重决议（重复键排除，样本守恒） ==="
W6B=$(curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-02-02","to":"2026-02-08","storeIds":["S001"],"route":"exact"}')
DUPSTATE=$(curl -s "$BASE/api/dist/orders?state=excluded" -H "$AUTH" | grep -c "O-DUP2" || true)
curl -s -X POST "$BASE/api/dist/revisions/dedup" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"canonicalKey":"O-DUP","duplicateKey":"O-DUP2"}' > /dev/null
W6A=$(curl -s -X POST "$BASE/api/dist/query" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-02-02","to":"2026-02-08","storeIds":["S001"],"route":"exact"}')
printf '%s\n' "$W6B" "$W6A" | DUPSTATE=$DUPSTATE python3 -c "
import sys,json,os
lines=[json.loads(l) for l in sys.stdin if l.strip().startswith('{')]
b,a=lines[0]['cells'][0],lines[1]['cells'][0]
already=int(os.environ.get('DUPSTATE','0'))>0
assert a['population']==b['population'], '订单总体不变'
if already:
    assert a['validSamples']==b['validSamples'], '幂等：已排除则样本不变'
else:
    assert a['validSamples']==b['validSamples']-1, '重复键排除后样本 -1'
    assert a['excludedCount']==b['excludedCount']+1, '排除数 +1'
assert a['population']==a['validSamples']+a['pendingCount']+a['excludedCount'], '样本守恒'
print('ok')" > /dev/null 2>&1 && ok "去重决议：重复键排除不复制订单，总体不变样本守恒" || bad "去重决议失效"

echo ""
echo "=========================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
echo "=========================================="
[ $FAIL -eq 0 ] && exit 0 || exit 1
