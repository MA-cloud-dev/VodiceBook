// ===== 全局状态 =====
let currentTaskId = null;
let currentChapterId = null;
let pollingTimer = null;

// ===== 颜色映射（角色标识用） =====
const characterColors = [
    '#6366f1', '#ec4899', '#10b981', '#f59e0b',
    '#3b82f6', '#8b5cf6', '#ef4444', '#14b8a6'
];

const statusLabels = {
    'PENDING': '等待处理',
    'ANALYZING': 'AI 分析中...',
    'SCRIPT_READY': '朗读稿就绪',
    'SYNTHESIZING': '语音合成中...',
    'COMPLETED': '已完成',
    'FAILED': '处理失败'
};

const emotionLabels = {
    'neutral': '平静', 'happy': '愉悦', 'angry': '愤怒',
    'sad': '悲伤', 'fearful': '恐惧', 'surprised': '惊讶',
    'gentle': '温柔', 'serious': '严肃', 'curious': '好奇', 'shy': '害羞'
};

// ===== 字数统计 =====
const contentEl = document.getElementById('chapter-content');
const wordCountEl = document.getElementById('word-count');

contentEl.addEventListener('input', () => {
    wordCountEl.textContent = contentEl.value.length;
});

// ===== 页面加载时获取历史任务 =====
document.addEventListener('DOMContentLoaded', loadHistory);

// ===== 上传章节 =====
async function uploadChapter() {
    const title = document.getElementById('chapter-title').value.trim();
    const content = document.getElementById('chapter-content').value.trim();

    if (!title) return showToast('请输入章节标题');
    if (!content) return showToast('请输入章节内容');
    if (content.length < 10) return showToast('章节内容太短，至少需要10个字');

    const btn = document.getElementById('btn-upload');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span> 上传中...';

    try {
        // 1. 创建章节
        const chapterRes = await fetch('/api/chapters', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, content })
        });
        if (!chapterRes.ok) throw new Error('章节创建失败');
        const chapter = await chapterRes.json();
        currentChapterId = chapter.id;

        // 2. 创建任务（自动开始分析）
        const taskRes = await fetch('/api/tasks', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ chapterId: chapter.id })
        });
        if (!taskRes.ok) throw new Error('任务创建失败');
        const task = await taskRes.json();
        currentTaskId = task.id;

        // 3. 显示状态面板，开始轮询
        setStep(2);
        showSection('status');
        startPolling();

    } catch (err) {
        showToast('操作失败: ' + err.message);
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<span class="btn-icon">🚀</span> 上传并分析';
    }
}

// ===== 状态轮询 =====
function startPolling() {
    stopPolling();
    pollStatus(); // 立即执行一次
    pollingTimer = setInterval(pollStatus, 2000);
}

function stopPolling() {
    if (pollingTimer) {
        clearInterval(pollingTimer);
        pollingTimer = null;
    }
}

async function pollStatus() {
    if (!currentTaskId) return;

    try {
        const res = await fetch(`/api/tasks/${currentTaskId}`);
        if (!res.ok) return;
        const task = await res.json();

        updateStatusPanel(task);

        if (task.status === 'SCRIPT_READY') {
            stopPolling();
            await loadScript();
        } else if (task.status === 'COMPLETED') {
            stopPolling();
            showAudioSection();
        } else if (task.status === 'FAILED') {
            stopPolling();
        }
    } catch (err) {
        console.error('轮询失败:', err);
    }
}

function updateStatusPanel(task) {
    const statusEl = document.getElementById('task-status');
    const progressEl = document.getElementById('task-progress');
    const progressBar = document.getElementById('progress-bar');
    const errorEl = document.getElementById('error-msg');

    const statusClass = task.status.toLowerCase().replace('_', '-');
    statusEl.textContent = statusLabels[task.status] || task.status;
    statusEl.className = 'status-badge ' + statusClass;

    progressEl.textContent = task.progress + '%';
    progressBar.style.width = task.progress + '%';

    if (task.status === 'FAILED' && task.errorMessage) {
        errorEl.textContent = task.errorMessage;
        errorEl.style.display = 'block';
    } else {
        errorEl.style.display = 'none';
    }

    document.getElementById('section-status').style.display = 'block';
}

// ===== 朗读稿展示 =====
async function loadScript() {
    try {
        const res = await fetch(`/api/tasks/${currentTaskId}/script`);
        if (!res.ok) throw new Error('获取朗读稿失败');
        const script = await res.json();

        renderScript(script);
        showSection('script');
        setStep(2);
    } catch (err) {
        showToast('加载朗读稿失败: ' + err.message);
    }
}

function renderScript(script) {
    // 渲染角色列表
    const charsEl = document.getElementById('characters-list');
    charsEl.innerHTML = '';
    if (script.characters) {
        script.characters.forEach((char, i) => {
            const color = characterColors[i % characterColors.length];
            const tag = document.createElement('span');
            tag.className = 'character-tag';
            tag.innerHTML = `<span class="character-dot" style="background:${color}"></span>${char.name}`;
            charsEl.appendChild(tag);
        });
    }

    // 构建角色名映射
    const charMap = {};
    if (script.characters) {
        script.characters.forEach((c, i) => {
            charMap[c.id] = { name: c.name, color: characterColors[i % characterColors.length] };
        });
    }

    // 渲染段落列表
    const segsEl = document.getElementById('segments-list');
    segsEl.innerHTML = '';
    if (script.segments) {
        script.segments.forEach(seg => {
            const char = charMap[seg.characterId] || { name: seg.characterId, color: '#666' };
            const emotionText = emotionLabels[seg.emotion] || seg.emotion;

            const item = document.createElement('div');
            item.className = 'segment-item ' + seg.type;
            item.innerHTML = `
                <div class="segment-meta">
                    <span class="char-name" style="color:${char.color}">${char.name}</span>
                    <span>${seg.type === 'dialogue' ? '对话' : '旁白'}</span>
                    <span class="emotion-label">${emotionText}</span>
                </div>
                <div class="segment-text">${escapeHtml(seg.text)}</div>
            `;
            segsEl.appendChild(item);
        });
    }
}

// ===== TTS 合成 =====
async function startSynthesis() {
    const btn = document.getElementById('btn-synthesize');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span> 合成中...';

    try {
        const res = await fetch(`/api/tasks/${currentTaskId}/synthesize`, {
            method: 'POST'
        });
        if (!res.ok) throw new Error('启动合成失败');

        setStep(3);
        startPolling();
    } catch (err) {
        showToast('启动合成失败: ' + err.message);
        btn.disabled = false;
        btn.innerHTML = '<span class="btn-icon">🎵</span> 开始语音合成';
    }
}

// ===== 音频展示 =====
function showAudioSection() {
    const player = document.getElementById('audio-player');
    player.src = `/api/tasks/${currentTaskId}/audio`;
    showSection('audio');
    setStep(3);

    // 恢复合成按钮状态
    const btn = document.getElementById('btn-synthesize');
    if (btn) {
        btn.disabled = false;
        btn.innerHTML = '<span class="btn-icon">🎵</span> 开始语音合成';
    }

    loadHistory();
}

function downloadAudio() {
    window.open(`/api/tasks/${currentTaskId}/audio`, '_blank');
}

// ===== 重置 =====
function resetAll() {
    currentTaskId = null;
    currentChapterId = null;
    stopPolling();

    document.getElementById('chapter-title').value = '';
    document.getElementById('chapter-content').value = '';
    document.getElementById('word-count').textContent = '0';

    document.getElementById('section-status').style.display = 'none';
    document.getElementById('section-script').style.display = 'none';
    document.getElementById('section-audio').style.display = 'none';
    document.getElementById('section-upload').style.display = 'block';

    setStep(1);
    loadHistory();
}

// ===== 任务历史 =====
async function loadHistory() {
    try {
        const res = await fetch('/api/tasks');
        if (!res.ok) return;
        const tasks = await res.json();

        const historyEl = document.getElementById('task-history');
        if (tasks.length === 0) {
            historyEl.innerHTML = '<p class="empty-hint">暂无历史任务</p>';
            return;
        }

        historyEl.innerHTML = '';
        for (const task of tasks) {
            const statusClass = task.status.toLowerCase().replace('_', '-');
            const item = document.createElement('div');
            item.className = 'history-item';
            item.innerHTML = `
                <div class="history-info">
                    <span class="history-title">任务 #${task.id} — 章节 #${task.chapterId}</span>
                    <span class="history-time">${formatTime(task.createdAt)}</span>
                </div>
                <span class="status-badge ${statusClass}">${statusLabels[task.status] || task.status}</span>
            `;
            historyEl.appendChild(item);
        }
    } catch (err) {
        console.error('加载历史失败:', err);
    }
}

// ===== 工具函数 =====
function showSection(name) {
    const sections = ['upload', 'status', 'script', 'audio'];
    // status 始终可见（只要有任务）
    if (name === 'script') {
        document.getElementById('section-script').style.display = 'block';
    }
    if (name === 'audio') {
        document.getElementById('section-audio').style.display = 'block';
    }
}

function setStep(num) {
    document.querySelectorAll('.step').forEach(el => {
        const s = parseInt(el.dataset.step);
        el.classList.remove('active', 'done');
        if (s < num) el.classList.add('done');
        if (s === num) el.classList.add('active');
    });
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function formatTime(isoStr) {
    if (!isoStr) return '';
    const d = new Date(isoStr);
    return d.toLocaleString('zh-CN');
}

function showToast(msg) {
    // 简单的 alert 替代，后续可升级为 toast UI
    alert(msg);
}
