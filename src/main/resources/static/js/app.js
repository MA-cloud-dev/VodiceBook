// ===== 认证辅助 =====
function getToken() {
    return sessionStorage.getItem('token');
}

function authHeaders() {
    const token = getToken();
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    return headers;
}

function checkAuth() {
    if (!getToken()) {
        window.location.href = '/login.html';
        return false;
    }
    const btnNew = document.getElementById('btn-new');
    if (btnNew) btnNew.style.display = 'inline-block';
    return true;
}

function logout() {
    sessionStorage.clear();
    window.location.href = '/login.html';
}

function showUserInfo() {
    const nickname = sessionStorage.getItem('nickname') || sessionStorage.getItem('username') || '';
    const userEl = document.getElementById('user-info');
    if (userEl && nickname) {
        userEl.textContent = nickname;
        userEl.style.display = 'inline';
    }
    const logoutBtn = document.getElementById('btn-logout');
    if (logoutBtn) logoutBtn.style.display = 'inline-block';
}

// ===== 全局状态 =====
let currentTaskId = null;
let currentChapterId = null;
let pollingTimer = null;
let isEditMode = false;
let currentScript = null;
let voiceData = { male: [], female: [] }; // 缓存音色列表
let voiceSampleMap = {}; // 音色名 -> 样本音频路径
let previewAudio = null; // 全局试听播放器

// ===== 状态保存防抖 =====
function debounce(func, wait) {
    let timeout;
    return function (...args) {
        clearTimeout(timeout);
        timeout = setTimeout(() => func.apply(this, args), wait);
    };
}

async function saveUserState() {
    if (!getToken()) return;
    const state = {
        taskId: currentTaskId,
        chapterId: currentChapterId,
        draftTitle: document.getElementById('chapter-title').value,
        draftContent: document.getElementById('chapter-content').value
    };
    try {
        await fetch('/api/user/state', {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify(state)
        });
    } catch (err) {
        console.error('Failed to save state:', err);
    }
}
const debouncedSaveState = debounce(saveUserState, 500);

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

const emotionOptions = Object.entries(emotionLabels);

const typeLabels = { 'narration': '旁白', 'dialogue': '对话' };
const subTypeLabels = { 'general': '通用叙述', 'inner_thought': '内心独白' };

// ===== 字数统计 =====
const contentEl = document.getElementById('chapter-content');
const wordCountEl = document.getElementById('word-count');

contentEl.addEventListener('input', () => {
    wordCountEl.textContent = contentEl.value.length;
    debouncedSaveState();
});

document.getElementById('chapter-title').addEventListener('input', debouncedSaveState);

// ===== 页面加载 =====
document.addEventListener('DOMContentLoaded', () => {
    if (!checkAuth()) return;
    showUserInfo();
    loadHistory();
    loadVoices().then(() => {
        loadUserState();
    });
});

async function loadUserState() {
    try {
        const res = await fetch('/api/user/state', { headers: authHeaders() });
        if (res.status === 401) return logout();
        if (!res.ok) return;
        const state = await res.json();

        if (state.draftTitle) document.getElementById('chapter-title').value = state.draftTitle;
        if (state.draftContent) {
            contentEl.value = state.draftContent;
            wordCountEl.textContent = state.draftContent.length;
        }

        if (state.taskId) {
            loadTaskDetails(state.taskId);
        }
    } catch (err) {
        console.error('Failed to load user state:', err);
    }
}

async function createNewDraft() {
    try {
        await fetch('/api/user/state', { method: 'DELETE', headers: authHeaders() });
    } catch (err) {
        console.error('Failed to clear state:', err);
    }
    resetAll();
}

// ===== 加载音色列表 =====
async function loadVoices() {
    try {
        const res = await fetch('/api/voices', { headers: authHeaders() });
        if (res.status === 401) return logout();
        if (res.ok) {
            voiceData = await res.json();
            // 构建音色样本映射表
            voiceSampleMap = {};
            [...(voiceData.male || []), ...(voiceData.female || [])].forEach(v => {
                if (v.sample) voiceSampleMap[v.name] = v.sample;
            });
        }
    } catch (err) {
        console.error('加载音色列表失败:', err);
    }
}

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
        const chapterRes = await fetch('/api/chapters', {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify({ title, content })
        });
        if (chapterRes.status === 401) return logout();
        if (!chapterRes.ok) throw new Error('章节创建失败');
        const chapter = await chapterRes.json();
        currentChapterId = chapter.id;

        const taskRes = await fetch('/api/tasks', {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify({ chapterId: chapter.id })
        });
        if (taskRes.status === 401) return logout();
        if (!taskRes.ok) throw new Error('任务创建失败');
        const task = await taskRes.json();
        currentTaskId = task.id;

        setStep(2);
        showSection('status');
        startPolling();
        saveUserState();
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
    pollStatus();
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
        const res = await fetch(`/api/tasks/${currentTaskId}`, { headers: authHeaders() });
        if (res.status === 401) return logout();
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
        const res = await fetch(`/api/tasks/${currentTaskId}/script`, { headers: authHeaders() });
        if (res.status === 401) return logout();
        if (!res.ok) throw new Error('获取朗读稿失败');
        const script = await res.json();

        currentScript = script;
        isEditMode = false;
        renderScript(script);
        showSection('script');
        setStep(2);
        updateEditButtons(false);
    } catch (err) {
        showToast('加载朗读稿失败: ' + err.message);
    }
}

function renderScript(script) {
    renderCharactersList(script, false);
    renderSegmentsList(script, false);
}

// ===== 角色列表渲染 =====
function renderCharactersList(script, editable) {
    const charsEl = document.getElementById('characters-list');
    charsEl.innerHTML = '';

    if (!script.characters) return;

    script.characters.forEach((char, i) => {
        const color = characterColors[i % characterColors.length];
        const genderIcon = char.gender === 'female' ? '♀' : char.gender === 'male' ? '♂' : '?';

        if (editable) {
            const card = document.createElement('div');
            card.className = 'character-card editing';
            card.dataset.index = i;

            const voiceOpts = buildVoiceOptions(char.gender, char.voice);

            card.innerHTML = `
                <div class="char-card-row">
                    <label>名称：</label>
                    <input type="text" class="char-edit-name" value="${escapeHtml(char.name)}" placeholder="角色名">
                </div>
                <div class="char-card-row">
                    <label>性别：</label>
                    <select class="char-edit-gender" onchange="onCharGenderChange(this)">
                        <option value="male" ${char.gender === 'male' ? 'selected' : ''}>男</option>
                        <option value="female" ${char.gender === 'female' ? 'selected' : ''}>女</option>
                        <option value="unknown" ${char.gender === 'unknown' ? 'selected' : ''}>未知</option>
                    </select>
                </div>
                <div class="char-card-row">
                    <label>音色：</label>
                    <select class="char-edit-voice" onchange="onVoiceChange(this)">${voiceOpts}</select>
                    <button class="btn-voice-preview" onclick="previewVoice(this)" title="试听音色">🔊</button>
                </div>
                <button class="btn-char-delete" onclick="deleteCharacter(${i})" title="删除角色">✕</button>
            `;
            charsEl.appendChild(card);
        } else {
            const voiceLabel = char.voice ? ` 🎤${char.voice}` : '';
            const tag = document.createElement('span');
            tag.className = 'character-tag';
            tag.innerHTML = `<span class="character-dot" style="background:${color}"></span>${char.name} <span class="gender-icon">${genderIcon}</span>${voiceLabel}`;
            charsEl.appendChild(tag);
        }
    });

    if (editable) {
        const addBtn = document.createElement('button');
        addBtn.className = 'btn btn-outline btn-add-char';
        addBtn.innerHTML = '➕ 添加角色';
        addBtn.onclick = addCharacter;
        charsEl.appendChild(addBtn);
    }
}

function buildVoiceOptions(gender, selectedVoice) {
    let options = '<option value="">自动分配</option>';
    const primary = gender === 'female' ? (voiceData.female || []) : (voiceData.male || []);
    const secondary = gender === 'female' ? (voiceData.male || []) : (voiceData.female || []);

    if (primary.length) {
        options += `<optgroup label="${gender === 'female' ? '女声' : '男声'}">`;
        primary.forEach(v => {
            options += `<option value="${v.name}" ${v.name === selectedVoice ? 'selected' : ''}>${v.label}</option>`;
        });
        options += '</optgroup>';
    }
    if (secondary.length) {
        options += `<optgroup label="${gender === 'female' ? '男声' : '女声'}">`;
        secondary.forEach(v => {
            options += `<option value="${v.name}" ${v.name === selectedVoice ? 'selected' : ''}>${v.label}</option>`;
        });
        options += '</optgroup>';
    }
    return options;
}

function onCharGenderChange(selectEl) {
    const card = selectEl.closest('.character-card');
    const voiceSelect = card.querySelector('.char-edit-voice');
    const currentVoice = voiceSelect.value;
    voiceSelect.innerHTML = buildVoiceOptions(selectEl.value, currentVoice);
}

function onVoiceChange(selectEl) {
    const voiceName = selectEl.value;
    if (voiceName && voiceSampleMap[voiceName]) {
        playVoiceSample(voiceSampleMap[voiceName]);
    }
}

function previewVoice(btn) {
    const card = btn.closest('.character-card');
    const voiceSelect = card.querySelector('.char-edit-voice');
    const voiceName = voiceSelect.value;
    if (!voiceName) {
        showToast('请先选择一个音色');
        return;
    }
    const sampleUrl = voiceSampleMap[voiceName];
    if (!sampleUrl) {
        showToast('该音色暂无试听样本');
        return;
    }
    playVoiceSample(sampleUrl);
}

function playVoiceSample(url) {
    if (previewAudio) {
        previewAudio.pause();
        previewAudio = null;
    }
    previewAudio = new Audio(url);
    previewAudio.play().catch(err => console.error('播放失败:', err));
}

function addCharacter() {
    if (!currentScript) return;
    const newId = 'char_' + Date.now();
    currentScript.characters.push({
        id: newId,
        name: '新角色',
        gender: 'unknown',
        voice: null,
        voiceProfile: null,
        description: ''
    });
    renderCharactersList(currentScript, true);
}

function deleteCharacter(index) {
    if (!currentScript || !currentScript.characters) return;
    if (currentScript.characters.length <= 1) {
        showToast('至少保留一个角色');
        return;
    }
    currentScript.characters.splice(index, 1);
    renderCharactersList(currentScript, true);
}

// ===== 段落列表渲染 =====
function renderSegmentsList(script, editable) {
    const charMap = {};
    if (script.characters) {
        script.characters.forEach((c, i) => {
            charMap[c.id] = { name: c.name, color: characterColors[i % characterColors.length], gender: c.gender };
        });
    }

    const segsEl = document.getElementById('segments-list');
    segsEl.innerHTML = '';
    if (!script.segments) return;

    script.segments.forEach((seg, idx) => {
        const char = charMap[seg.characterId] || { name: seg.characterId, color: '#666' };
        const emotionText = emotionLabels[seg.emotion] || seg.emotion;
        const subTypeText = seg.subType ? (subTypeLabels[seg.subType] || seg.subType) : '';

        let innerLabel = '';
        if (seg.subType === 'inner_thought' && seg.characterId && seg.characterId !== 'narrator') {
            const attachedChar = charMap[seg.characterId];
            if (attachedChar) {
                innerLabel = `<span class="inner-attach-badge">💭 ${attachedChar.name}的内心</span>`;
            }
        }

        const item = document.createElement('div');
        item.className = 'segment-item ' + seg.type;
        item.dataset.index = idx;

        if (editable) {
            const charOptions = script.characters
                .map(c => `<option value="${c.id}" ${c.id === seg.characterId ? 'selected' : ''}>${c.name}</option>`)
                .join('');

            const emotionOpts = emotionOptions
                .map(([k, v]) => `<option value="${k}" ${k === seg.emotion ? 'selected' : ''}>${v}</option>`)
                .join('');

            const typeOpts = `
                <option value="narration" ${seg.type === 'narration' ? 'selected' : ''}>旁白</option>
                <option value="dialogue" ${seg.type === 'dialogue' ? 'selected' : ''}>对话</option>
            `;

            const subTypeOpts = `
                <option value="" ${!seg.subType ? 'selected' : ''}>无</option>
                <option value="general" ${seg.subType === 'general' ? 'selected' : ''}>通用叙述</option>
                <option value="inner_thought" ${seg.subType === 'inner_thought' ? 'selected' : ''}>内心独白</option>
            `;

            item.className += ' editing';
            item.innerHTML = `
                <div class="segment-edit-form">
                    <div class="edit-row">
                        <label>角色：</label>
                        <select class="edit-character">${charOptions}</select>
                        <label>类型：</label>
                        <select class="edit-type" onchange="onTypeChange(this)">${typeOpts}</select>
                        <label>子类型：</label>
                        <select class="edit-subtype" ${seg.type === 'dialogue' ? 'disabled' : ''}>${subTypeOpts}</select>
                        <label>情感：</label>
                        <select class="edit-emotion">${emotionOpts}</select>
                    </div>
                    <textarea class="edit-text" rows="2">${escapeHtml(seg.text)}</textarea>
                </div>
            `;
        } else {
            item.innerHTML = `
                <div class="segment-meta">
                    <span class="seg-index">#${idx}</span>
                    <span class="char-name" style="color:${char.color}">${char.name}</span>
                    <span class="type-badge">${seg.type === 'dialogue' ? '对话' : '旁白'}</span>
                    ${subTypeText ? `<span class="subtype-badge">${subTypeText}</span>` : ''}
                    ${innerLabel}
                    <span class="emotion-label">${emotionText}</span>
                </div>
                <div class="segment-text">${escapeHtml(seg.text)}</div>
            `;
        }
        segsEl.appendChild(item);
    });
}

// ===== 编辑模式 =====
function toggleEditMode() {
    if (!currentScript) return;
    isEditMode = true;
    updateEditButtons(true);
    renderCharactersList(currentScript, true);
    renderSegmentsList(currentScript, true);
}

function updateEditButtons(editing) {
    document.getElementById('btn-edit-mode').style.display = editing ? 'none' : '';
    document.getElementById('btn-save-script').style.display = editing ? '' : 'none';
    document.getElementById('btn-cancel-edit').style.display = editing ? '' : 'none';
    document.getElementById('btn-ai-regen').style.display = editing ? 'none' : '';
    document.getElementById('btn-synthesize').disabled = editing;
}

function cancelEdit() {
    isEditMode = false;
    updateEditButtons(false);
    renderScript(currentScript);
}

function onTypeChange(selectEl) {
    const subTypeSelect = selectEl.closest('.edit-row').querySelector('.edit-subtype');
    if (selectEl.value === 'dialogue') {
        subTypeSelect.value = '';
        subTypeSelect.disabled = true;
    } else {
        subTypeSelect.disabled = false;
    }
}

async function saveScript() {
    if (!currentScript || !currentTaskId) return;

    const charCards = document.querySelectorAll('.character-card.editing');
    const updatedChars = [];
    charCards.forEach((card, i) => {
        const origChar = currentScript.characters[i] || {};
        updatedChars.push({
            id: origChar.id || ('char_' + i),
            name: card.querySelector('.char-edit-name').value.trim() || '未命名',
            gender: card.querySelector('.char-edit-gender').value,
            voice: card.querySelector('.char-edit-voice').value || null,
            voiceProfile: origChar.voiceProfile || null,
            description: origChar.description || ''
        });
    });

    const segments = [];
    const items = document.querySelectorAll('.segment-item.editing');
    items.forEach((item, idx) => {
        segments.push({
            index: idx,
            type: item.querySelector('.edit-type').value,
            subType: item.querySelector('.edit-subtype').value || null,
            characterId: item.querySelector('.edit-character').value,
            emotion: item.querySelector('.edit-emotion').value,
            text: item.querySelector('.edit-text').value
        });
    });

    const updatedScript = {
        chapterId: currentScript.chapterId,
        title: currentScript.title,
        characters: updatedChars,
        segments: segments
    };

    try {
        const res = await fetch(`/api/tasks/${currentTaskId}/script`, {
            method: 'PUT',
            headers: authHeaders(),
            body: JSON.stringify(updatedScript)
        });
        if (res.status === 401) return logout();
        if (!res.ok) throw new Error('保存失败');

        currentScript = updatedScript;
        isEditMode = false;
        updateEditButtons(false);
        renderScript(updatedScript);
        showToast('朗读稿已保存 ✅');
    } catch (err) {
        showToast('保存失败: ' + err.message);
    }
}

// ===== AI 重新生成 =====
function showRegenDialog() {
    document.getElementById('regen-dialog').style.display = 'block';
    document.getElementById('regen-instruction').focus();
}

function hideRegenDialog() {
    document.getElementById('regen-dialog').style.display = 'none';
    document.getElementById('regen-instruction').value = '';
}

async function regenerateScript() {
    const instruction = document.getElementById('regen-instruction').value.trim();
    if (!instruction) return showToast('请输入修改意见');
    if (!currentTaskId) return;

    const btn = document.getElementById('btn-regen-submit');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span> AI 处理中...';

    try {
        const res = await fetch(`/api/tasks/${currentTaskId}/script/regenerate`, {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify({ instruction: instruction })
        });
        if (res.status === 401) return logout();
        if (!res.ok) throw new Error('提交失败');
        hideRegenDialog();
        startPolling();
    } catch (err) {
        showToast('提交失败: ' + err.message);
    } finally {
        btn.disabled = false;
        btn.innerHTML = '🚀 提交给 AI';
    }
}

// ===== TTS 合成 =====
async function startSynthesis() {
    const btn = document.getElementById('btn-synthesize');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span> 合成中...';

    try {
        const res = await fetch(`/api/tasks/${currentTaskId}/synthesize`, {
            method: 'POST',
            headers: authHeaders()
        });
        if (res.status === 401) return logout();
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
    const token = getToken();
    player.src = `/api/tasks/${currentTaskId}/audio?token=${encodeURIComponent(token)}`;
    showSection('audio');
    setStep(3);

    const btn = document.getElementById('btn-synthesize');
    if (btn) {
        btn.disabled = false;
        btn.innerHTML = '<span class="btn-icon">🎵</span> 开始语音合成';
    }
    loadHistory();
}

function downloadAudio() {
    const token = getToken();
    window.open(`/api/tasks/${currentTaskId}/audio?token=${encodeURIComponent(token)}`, '_blank');
}

// ===== 重置 =====
function resetAll() {
    currentTaskId = null;
    currentChapterId = null;
    currentScript = null;
    isEditMode = false;
    stopPolling();

    document.getElementById('chapter-title').value = '';
    document.getElementById('chapter-content').value = '';
    document.getElementById('word-count').textContent = '0';

    document.getElementById('section-status').style.display = 'none';
    document.getElementById('section-script').style.display = 'none';
    document.getElementById('section-audio').style.display = 'none';
    document.getElementById('section-upload').style.display = 'block';

    // Enable inputs
    document.getElementById('chapter-title').readOnly = false;
    document.getElementById('chapter-content').readOnly = false;
    document.getElementById('btn-upload').style.display = 'inline-block';

    setStep(1);
    loadHistory();
}

// ===== 任务历史 =====
async function loadHistory() {
    try {
        const res = await fetch('/api/tasks', { headers: authHeaders() });
        if (res.status === 401) return logout();
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
            item.onclick = () => loadTaskDetails(task.id);
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

async function loadTaskDetails(taskId) {
    try {
        const res = await fetch(`/api/tasks/${taskId}`, { headers: authHeaders() });
        if (res.status === 401) return logout();
        if (!res.ok) throw new Error('无法加载任务详情');
        const task = await res.json();

        currentTaskId = task.id;
        currentChapterId = task.chapterId;
        saveUserState();

        // Load chapter text
        try {
            const chapRes = await fetch(`/api/chapters/${task.chapterId}`, { headers: authHeaders() });
            if (chapRes.ok) {
                const chap = await chapRes.json();
                document.getElementById('chapter-title').value = chap.title;
                document.getElementById('chapter-content').value = chap.content;
                document.getElementById('word-count').textContent = chap.content.length;
            }
        } catch (e) {
            console.warn('Failed to fetch chapter', e);
        }

        // 保持 section-upload 可见，但禁用输入
        document.getElementById('section-upload').style.display = 'block';
        document.getElementById('chapter-title').readOnly = true;
        document.getElementById('chapter-content').readOnly = true;
        document.getElementById('btn-upload').style.display = 'none';

        if (task.status === 'PENDING' || task.status === 'ANALYZING') {
            setStep(1);
            showSection('status');
            document.getElementById('section-script').style.display = 'none';
            document.getElementById('section-audio').style.display = 'none';
            startPolling();
        } else if (task.status === 'SCRIPT_READY') {
            setStep(2);
            showSection('status');
            document.getElementById('section-audio').style.display = 'none';
            startPolling(); // it will fetch script and transition
        } else if (task.status === 'SYNTHESIZING') {
            setStep(3);
            showSection('status');
            startPolling(); // wait for TTS
        } else if (task.status === 'COMPLETED') {
            document.getElementById('section-status').style.display = 'none';
            // 手动渲染一次script
            loadScript().then(() => {
                showAudioSection(); // section-upload, section-script, section-audio 全展示
            });
        } else if (task.status === 'FAILED') {
            setStep(1);
            showSection('status');
            document.getElementById('task-status-text').textContent = '任务执行失败: ' + (task.errorMessage || '');
        }
    } catch (err) {
        showToast(err.message);
    }
}

// ===== 工具函数 =====
function showSection(name) {
    if (name === 'script') document.getElementById('section-script').style.display = 'block';
    if (name === 'audio') document.getElementById('section-audio').style.display = 'block';
    if (name === 'status') document.getElementById('section-status').style.display = 'block';
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
    alert(msg);
}
