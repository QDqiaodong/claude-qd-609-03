<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">计分回合 <em>环数键盘一支一支记 · 顺序即射箭顺序</em></h2>
      <div class="page-tools">
        <el-tag type="info" effect="plain">键盘：{{ (keypad.rings || []).join(' / ') }}</el-tag>
        <el-tag effect="plain">每组 {{ (keypad.groupSizes || []).join(' 或 ') }} 支</el-tag>
      </div>
    </div>

    <!-- 开一个新回合 -->
    <div class="panel">
      <div class="panel-title">开新回合 <small>选会员 → 选箭道 → 选一组 6 支或 12 支</small></div>
      <div class="start-row">
        <el-select v-model="startForm.memberId" placeholder="会员" style="width: 210px">
          <el-option v-for="m in members" :key="m.id" :label="`${m.name} · ${m.cardNo}`" :value="m.id" />
        </el-select>
        <el-select v-model="startForm.laneId" placeholder="箭道" style="width: 190px">
          <el-option
            v-for="l in lanes"
            :key="l.id"
            :label="`${l.laneNo} · ${l.distance}m · ${l.statusName}`"
            :value="l.id"
            :disabled="l.status === 'MAINTENANCE' || l.status === 'LOCKED'"
          />
        </el-select>
        <el-radio-group v-model="startForm.arrowCount">
          <el-radio-button v-for="size in keypad.groupSizes || [6, 12]" :key="size" :value="size">
            {{ size }} 支
          </el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="startForm.bowType">
          <el-radio-button value="RECURVE">反曲弓</el-radio-button>
          <el-radio-button value="COMPOUND">复合弓</el-radio-button>
          <el-radio-button value="TRADITIONAL">传统弓</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="startRound">开打</el-button>
      </div>
    </div>

    <!-- 计分键盘 + 本回合实时面板 -->
    <div class="panel">
      <div class="panel-title">
        计分键盘
        <small v-if="current">回合 {{ current.round.roundNo }} · {{ current.round.memberName }} · {{ current.round.laneNo }} 道</small>
        <small v-else>先开一个回合，再点键盘记环数</small>
      </div>

      <div class="desk">
        <!-- 左：键盘 -->
        <div class="keypad-box">
          <div class="keypad">
            <button
              v-for="ring in keypad.rings || []"
              :key="ring"
              type="button"
              class="key"
              :class="keyClass(ring)"
              :disabled="!canShoot"
              @click="shoot(ring)"
            >
              {{ ring }}
            </button>
          </div>
          <div class="key-actions">
            <el-button :disabled="!canUndo" @click="undoShot">撤销最后一支</el-button>
            <el-button type="primary" :disabled="!canSubmit" @click="submitRound">提交回合</el-button>
          </div>
          <div class="muted keypad-hint">
            每点一下，按 <span class="mono">shot_index</span> 顺序 append 一支箭；打满一组才能提交。
          </div>
        </div>

        <!-- 右：本回合 -->
        <div class="live">
          <template v-if="current">
            <div class="live-head">
              <div>
                <span class="live-no mono">{{ current.round.roundNo }}</span>
                <el-tag size="small" :type="roundTagType(current.round.status)" effect="dark">
                  {{ current.round.statusName }}
                </el-tag>
                <el-tag v-if="current.round.personalBest" size="small" type="danger" effect="dark">本组个人最好</el-tag>
              </div>
              <div class="muted">{{ shortTime(current.round.startTime) }} · {{ current.round.groupLabel }}</div>
            </div>

            <div v-if="current.round.status === 'PAUSED'" class="pause-tip">
              安全停射事件联锁中：本回合已暂停，已记箭支与分数保留，放行前不能记箭、撤销或提交。
            </div>

            <div class="progress-line">
              <div class="progress-bar">
                <i :style="{ width: progressPercent + '%' }" />
              </div>
              <span class="mono">{{ current.arrows.length }} / {{ current.round.arrowCount }}</span>
            </div>

            <div class="shot-seq">
              <span
                v-for="slot in slots"
                :key="slot.index"
                class="slot"
                :class="{ 'is-empty': !slot.ring }"
              >
                <em class="slot-no">{{ slot.index }}</em>
                <span v-if="slot.ring" class="ring-chip" :class="chipClass(slot.ring)">{{ slot.ring }}</span>
                <span v-else class="ring-chip is-empty">·</span>
              </span>
            </div>

            <div class="score-cards">
              <div class="score-card">
                <span class="score-label">总分</span>
                <b class="score-value">{{ current.round.totalScore }}</b>
              </div>
              <div class="score-card">
                <span class="score-label">平均环</span>
                <b class="score-value">{{ Number(current.round.averageScore).toFixed(2) }}</b>
              </div>
              <div class="score-card">
                <span class="score-label">X 环</span>
                <b class="score-value">{{ xCount }}</b>
              </div>
              <div class="score-card">
                <span class="score-label">脱靶</span>
                <b class="score-value">{{ missCount }}</b>
              </div>
            </div>
          </template>
          <el-empty v-else description="还没有进行中的回合" :image-size="70" />
        </div>
      </div>
    </div>

    <!-- 历史回合 -->
    <div class="panel">
      <div class="panel-title">
        历史回合
        <small>按可比组别（会员 · 弓种 · 射距 · 箭数）各自比较 · 展开看每支箭的环数明细</small>
        <el-button class="repair-btn" size="small" type="warning" plain :loading="repairing" @click="runRepair">
          重算历史个人最好
        </el-button>
      </div>
      <el-table :data="rounds" row-key="id" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-box">
              <template v-if="details[row.id] && details[row.id].length">
                <span v-for="arrow in details[row.id]" :key="arrow.shotIndex" class="arrow-item">
                  <span class="ring-chip" :class="chipClass(arrow.ring)">{{ arrow.ring }}</span>
                  <em class="muted">第 {{ arrow.shotIndex }} 支 · {{ arrow.ringValue }} 环</em>
                </span>
              </template>
              <span v-else class="muted">本回合还没有记箭</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="roundNo" label="回合号" width="140" />
        <el-table-column prop="memberName" label="会员" width="90" />
        <el-table-column label="可比组别" min-width="210">
          <template #default="{ row }">
            <span class="group-label">{{ row.groupLabel }}</span>
            <el-tag v-if="row.personalBest" size="small" type="danger" effect="dark" class="pb-tag">本组个人最好</el-tag>
            <el-tooltip v-if="!row.eligible" :content="row.exclusionReasonName || '不参与个人最好比较'" placement="top">
              <el-tag size="small" type="info" effect="plain" class="pb-tag">不参与比较</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="箭道" width="100">
          <template #default="{ row }">{{ row.laneNo }} · {{ row.distance }}m</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="140" :formatter="(r) => shortTime(r.startTime)" />
        <el-table-column label="箭支" width="80">
          <template #default="{ row }">{{ row.shotCount }} / {{ row.arrowCount }}</template>
        </el-table-column>
        <el-table-column prop="totalScore" label="总分" width="70" />
        <el-table-column label="平均环" width="80">
          <template #default="{ row }">{{ Number(row.averageScore).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="roundTagType(row.status)" effect="plain">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="pickRound(row.id)">载入到键盘</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="repairReport" class="repair-report">
        <div class="repair-summary">
          最近一次修复：共 {{ repairReport.totalRounds }} 个回合，{{ repairReport.groupCount }} 个可比组别，
          个人最好 {{ repairReport.personalBestCount }} 条；本次翻转标记 {{ repairReport.changedMarks }} 个
          （重复执行为 0 即结果已稳定），{{ repairReport.excludedCount }} 个回合不参与比较。
        </div>
        <el-table v-if="repairReport.excludedRounds && repairReport.excludedRounds.length"
          :data="repairReport.excludedRounds" size="small" border max-height="240">
          <el-table-column prop="roundNo" label="回合号" width="140" />
          <el-table-column prop="memberName" label="会员" width="90" />
          <el-table-column label="弓种 / 射距 / 箭数" min-width="170">
            <template #default="{ row }">
              {{ row.bowTypeName || '弓种未知' }} · {{ row.distance == null ? '射距未知' : row.distance + 'm' }}
              · {{ row.shotCount }}/{{ row.arrowCount }} 支
            </template>
          </el-table-column>
          <el-table-column prop="statusName" label="状态" width="90" />
          <el-table-column prop="exclusionReasonName" label="不参与原因" min-width="200" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { memberApi, laneApi, roundApi, shortTime } from '../api'

const rounds = ref([])
const members = ref([])
const lanes = ref([])
const keypad = ref({ rings: [], groupSizes: [6, 12] })
const current = ref(null)
const details = reactive({})
const repairing = ref(false)
const repairReport = ref(null)

const startForm = reactive({ memberId: null, laneId: null, arrowCount: 6, bowType: 'RECURVE' })

const canShoot = computed(() => !!current.value && current.value.round.status === 'ONGOING')
const canUndo = computed(() => canShoot.value && current.value.arrows.length > 0)
const canSubmit = computed(() =>
  !!current.value && current.value.round.status === 'ONGOING'
  && current.value.arrows.length === current.value.round.arrowCount
)

const progressPercent = computed(() => {
  if (!current.value) return 0
  return (current.value.arrows.length / current.value.round.arrowCount) * 100
})

const slots = computed(() => {
  if (!current.value) return []
  const list = []
  for (let i = 1; i <= current.value.round.arrowCount; i += 1) {
    const hit = current.value.arrows.find((a) => a.shotIndex === i)
    list.push({ index: i, ring: hit ? hit.ring : null })
  }
  return list
})

const xCount = computed(() => current.value ? current.value.arrows.filter((a) => a.ring === 'X').length : 0)
const missCount = computed(() => current.value ? current.value.arrows.filter((a) => a.ring === 'M').length : 0)

function chipClass(ring) {
  if (ring === 'X') return 'is-x'
  if (ring === '10') return 'is-ten'
  if (ring === 'M') return 'is-miss'
  return ''
}

function roundTagType(status) {
  if (status === 'SUBMITTED') return 'success'
  if (status === 'PAUSED') return 'danger'
  return 'warning'
}

function keyClass(ring) {
  return 'key-' + (ring === 'X' ? 'x' : ring === '10' ? 'ten' : ring === 'M' ? 'miss' : 'normal')
}

async function loadRounds() {
  rounds.value = await roundApi.list()
}

async function loadBase() {
  members.value = await memberApi.options()
  const all = await laneApi.list()
  lanes.value = all
  if (!startForm.laneId && all.length) {
    const usable = all.find((l) => l.status !== 'MAINTENANCE' && l.status !== 'LOCKED')
    startForm.laneId = (usable || all[0]).id
  }
  if (!startForm.memberId && members.value.length) startForm.memberId = members.value[0].id
}

async function startRound() {
  if (!startForm.memberId || !startForm.laneId) {
    ElMessage.warning('请选择会员与箭道')
    return
  }
  const detail = await roundApi.start({
    memberId: startForm.memberId,
    laneId: startForm.laneId,
    bowType: startForm.bowType,
    arrowCount: startForm.arrowCount
  })
  current.value = detail
  await loadRounds()
  await loadBase()
}

async function shoot(ring) {
  if (!current.value) {
    ElMessage.warning('请先开一个回合')
    return
  }
  current.value = await roundApi.shoot(current.value.round.id, ring)
  await loadRounds()
}

async function undoShot() {
  current.value = await roundApi.undo(current.value.round.id)
  await loadRounds()
}

async function submitRound() {
  current.value = await roundApi.submit(current.value.round.id)
  ElMessage.success('回合已提交')
  await loadRounds()
}

async function pickRound(id) {
  current.value = await roundApi.detail(id)
}

async function onExpand(row) {
  if (details[row.id]) return
  const detail = await roundApi.detail(row.id)
  details[row.id] = detail.arrows
}

/** 历史修复：按可比组别重算个人最好标记（幂等，可重复执行） */
async function runRepair() {
  repairing.value = true
  try {
    repairReport.value = await roundApi.repairPersonalBests()
    ElMessage.success(
      `重算完成：${repairReport.value.personalBestCount} 条个人最好，翻转 ${repairReport.value.changedMarks} 个标记`
    )
    await loadRounds()
    if (current.value) {
      current.value = await roundApi.detail(current.value.round.id)
    }
  } finally {
    repairing.value = false
  }
}

onMounted(async () => {
  keypad.value = await roundApi.keypad()
  await loadBase()
  await loadRounds()
})
</script>

<style scoped>
.start-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.desk {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 16px;
}

/* ---------- 自绘计分键盘 ---------- */
.keypad {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.key {
  height: 62px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  font-size: 22px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--ink);
  cursor: pointer;
  box-shadow: 0 2px 0 #dfe6e5;
  transition: transform 0.08s, box-shadow 0.08s, background 0.15s;
}

.key:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(0, 105, 92, 0.18);
}

.key:active:not(:disabled) {
  transform: translateY(2px);
  box-shadow: 0 1px 0 #dfe6e5;
}

.key:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.key-x {
  background: linear-gradient(135deg, #00695c, #26a69a);
  border-color: #00503f;
  color: #fff;
}

.key-ten {
  background: #fff6d8;
  border-color: #f0d47c;
  color: #8a6100;
}

.key-miss {
  background: #f1f3f4;
  color: #98a3a8;
}

.key-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.keypad-hint {
  margin-top: 8px;
  line-height: 1.6;
}

/* ---------- 实时面板 ---------- */
.live {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  background: #fbfdfd;
  min-height: 220px;
}

.live-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.pause-tip {
  margin-top: 10px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #b3261e;
  background: #fdecec;
  border-left: 3px solid #c62828;
  border-radius: 4px;
}

.live-no {
  font-size: 14px;
  font-weight: 700;
  margin-right: 8px;
}

.progress-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0 12px;
}

.progress-bar {
  flex: 1;
  height: 8px;
  border-radius: 999px;
  background: #e6eeec;
  overflow: hidden;
}

.progress-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #26a69a, #00695c);
  transition: width 0.2s;
}

.shot-seq {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  background: #fff;
  min-height: 62px;
}

.slot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

.slot-no {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-mute);
}

.ring-chip.is-empty {
  background: #f7f9f9;
  border-style: dashed;
  color: #c3ccce;
}

.score-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-top: 12px;
}

.score-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 4px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.score-label {
  font-size: 11px;
  color: var(--ink-mute);
}

.score-value {
  font-size: 20px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--el-color-primary);
}

.expand-box {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 8px 12px;
  background: #fafcfc;
}

.arrow-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.group-label {
  font-size: 12px;
  white-space: nowrap;
}

.pb-tag {
  margin-left: 6px;
}

.repair-btn {
  margin-left: auto;
}

.repair-report {
  margin-top: 12px;
}

.repair-summary {
  font-size: 12px;
  color: var(--ink-mute);
  margin-bottom: 8px;
  line-height: 1.7;
}

@media (max-width: 1100px) {
  .desk {
    grid-template-columns: 1fr;
  }
}
</style>
