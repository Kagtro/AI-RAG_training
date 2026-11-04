/**
 * 前端逻辑（原生 JS，无框架）
 *
 * 三块功能：
 * 1. 上传文档 + 轮询解析状态
 * 2. 文档列表 + 重建索引
 * 3. 聊天：用 EventSource 接收后端 SSE 流式回答
 *
 * 说明：所有接口都是同源（页面由 Spring Boot 提供），所以不需要写域名。
 */

// ========== 全局状态 ==========
const state = {
    kbId: 1,          // 当前知识库 id
    streaming: false, // 是否正在接收回答（防止重复发送）
    selectedFiles: [],// 待上传文件（支持多选）
};

// 文档状态：0 待解析 / 1 解析中 / 2 已就绪 / 3 失败
const STATUS_TEXT = {
    0: '待解析',
    1: '解析中',
    2: '已就绪',
    3: '失败',
};

// 常用 DOM
const $ = (selector) => document.querySelector(selector);

// ========== 通用请求封装 ==========
async function api(path, options) {
    const response = await fetch(path, options);
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`请求失败 ${response.status}: ${text}`);
    }
    return response.json();
}

// ========== 健康检查 ==========
async function checkHealth() {
    const el = $('#health');
    try {
        const data = await api('/api/health');
        el.textContent = `服务正常 · 数据库 ${data.db}`;
        el.className = 'health ok';
    } catch (e) {
        el.textContent = '服务异常';
        el.className = 'health bad';
    }
}

// ========== 文档列表 ==========
async function loadDocuments() {
    const container = $('#docs');
    container.textContent = '加载中...';
    try {
        const docs = await api(`/api/documents?kbId=${state.kbId}`);
        renderDocuments(docs);
    } catch (e) {
        container.textContent = `加载失败：${e.message}`;
    }
}

function renderDocuments(docs) {
    const container = $('#docs');
    container.innerHTML = '';

    if (!docs || docs.length === 0) {
        container.textContent = '还没有文档，先上传一个吧。';
        return;
    }

    for (const doc of docs) {
        const item = document.createElement('div');
        item.className = 'doc-item';

        const title = document.createElement('div');
        title.className = 'doc-title';
        title.textContent = doc.file_name || `文档 #${doc.id}`;
        item.appendChild(title);

        const meta = document.createElement('div');
        meta.className = 'doc-meta';
        meta.textContent = `ID ${doc.id} · 切片 ${doc.chunk_count ?? 0} 个 · ${formatTime(doc.created_at)}`;
        item.appendChild(meta);

        const status = document.createElement('span');
        status.className = `status s${doc.status}`;
        status.textContent = STATUS_TEXT[doc.status] || `状态 ${doc.status}`;
        title.appendChild(status);

        const actions = document.createElement('div');
        actions.className = 'doc-actions';
        const reindexBtn = document.createElement('button');
        reindexBtn.className = 'btn ghost small';
        reindexBtn.textContent = '重建索引';
        reindexBtn.addEventListener('click', () => reindex(doc.id, doc.file_name));
        actions.appendChild(reindexBtn);

        const deleteBtn = document.createElement('button');
        deleteBtn.className = 'btn danger small';
        deleteBtn.textContent = '删除';
        deleteBtn.addEventListener('click', () => deleteDocument(doc.id, doc.file_name));
        actions.appendChild(deleteBtn);

        item.appendChild(actions);

        container.appendChild(item);
    }
}

function formatTime(value) {
    if (!value) return '';
    const date = new Date(value);
    return isNaN(date.getTime()) ? '' : date.toLocaleString();
}

// ========== 上传（支持多选） ==========
function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

// 合并新选择的文件，按 名字+大小+修改时间 去重
function addSelectedFiles(fileList) {
    for (const file of fileList) {
        const exists = state.selectedFiles.some((f) =>
            f.name === file.name && f.size === file.size && f.lastModified === file.lastModified);
        if (!exists) state.selectedFiles.push(file);
    }
    renderSelectedFiles();
}

function removeSelectedFile(index) {
    state.selectedFiles.splice(index, 1);
    renderSelectedFiles();
}

function clearSelectedFiles() {
    state.selectedFiles = [];
    renderSelectedFiles();
}

function renderSelectedFiles() {
    const files = state.selectedFiles;
    const box = $('#selectedFiles');
    const list = $('#selectedList');
    const count = $('#selectedCount');
    const title = $('#dropzoneTitle');
    const buttonText = $('#uploadBtnText');

    box.hidden = files.length === 0;
    count.textContent = String(files.length);
    title.textContent = files.length === 0 ? '选择文件' : '继续添加文件';
    buttonText.textContent = files.length === 0 ? '上传并索引' : `上传并索引（${files.length}）`;

    list.innerHTML = '';
    files.forEach((file, index) => {
        const item = document.createElement('div');
        item.className = 'selected-item';

        const main = document.createElement('div');
        main.className = 'selected-item-main';
        const name = document.createElement('span');
        name.className = 'selected-item-name';
        name.textContent = file.name;
        name.title = file.name;
        const size = document.createElement('span');
        size.className = 'selected-item-size';
        size.textContent = formatSize(file.size);
        main.appendChild(name);
        main.appendChild(size);

        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'selected-item-remove';
        remove.textContent = '×';
        remove.title = '移除';
        remove.addEventListener('click', () => removeSelectedFile(index));

        item.appendChild(main);
        item.appendChild(remove);
        list.appendChild(item);
    });
}

async function uploadFiles() {
    const hint = $('#uploadHint');
    const button = $('#uploadBtn');
    const files = state.selectedFiles;

    if (files.length === 0) {
        hint.textContent = '请先选择文件。';
        return;
    }

    button.disabled = true;
    $('#uploadBtnText').textContent = '上传中...';

    const uploaded = [];
    const failed = [];

    try {
        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            hint.textContent = `正在上传 ${i + 1}/${files.length}：${file.name}`;
            const formData = new FormData();
            formData.append('kbId', String(state.kbId));
            formData.append('file', file);
            try {
                const result = await api('/api/documents', { method: 'POST', body: formData });
                uploaded.push(result);
            } catch (e) {
                failed.push(`${file.name}（${e.message}）`);
            }
        }

        if (uploaded.length > 0) {
            hint.textContent = `已上传 ${uploaded.length} 个，正在解析、切片、向量化...`;
            await Promise.all(uploaded.map((doc) => pollDocumentStatus(doc.id)));
        }

        await loadDocuments();

        const ready = uploaded.length;
        if (failed.length === 0) {
            hint.textContent = `完成：${ready} 个文档已上传并索引。`;
        } else {
            hint.textContent = `完成 ${ready} 个，失败 ${failed.length} 个：${failed.join('；')}`;
        }
        clearSelectedFiles();
    } catch (e) {
        hint.textContent = `上传失败：${e.message}`;
    } finally {
        button.disabled = false;
        renderSelectedFiles();
    }
}

// 轮询单个文档状态，直到已就绪(2)或失败(3)
async function pollDocumentStatus(docId) {
    for (let i = 0; i < 20; i++) {
        await sleep(1500);
        try {
            const data = await api(`/api/documents/${docId}/status`);
            const doc = data.data;
            if (doc && (doc.status === 2 || doc.status === 3)) {
                return doc;
            }
        } catch (e) {
            // 忽略单次轮询失败，继续下一轮
        }
    }
    return null;
}

// 重建索引（比如换了 Embedding 模型/切片参数后）
async function reindex(docId, fileName) {
    const hint = $('#uploadHint');
    hint.textContent = `正在重建 ${fileName} 的索引...`;
    try {
        await api(`/api/documents/${docId}/reindex`, { method: 'POST' });
        await pollDocumentStatus(docId);
        await loadDocuments();
        hint.textContent = `${fileName} 索引重建完成。`;
    } catch (e) {
        hint.textContent = `重建失败：${e.message}`;
    }
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

// 删除文档：数据库记录 + 切片 + uploads 原文件
async function deleteDocument(docId, fileName) {
    const ok = confirm(`确定删除「${fileName}」吗？\n会同时删除数据库记录、切片和 uploads 里的原文件，无法恢复。`);
    if (!ok) return;

    const hint = $('#uploadHint');
    try {
        const response = await fetch(`/api/documents/${docId}`, { method: 'DELETE' });
        if (!response.ok) {
            const data = await response.json().catch(() => ({}));
            throw new Error(data.msg || `HTTP ${response.status}`);
        }
        hint.textContent = `已删除 ${fileName}`;
        await loadDocuments();
    } catch (e) {
        hint.textContent = `删除失败：${e.message}`;
    }
}

// ========== 聊天 ==========
function appendMessage(role, text) {
    // 第一次发消息时移除空状态提示
    const emptyTip = $('.empty-tip');
    if (emptyTip) emptyTip.remove();

    const wrapper = document.createElement('div');
    wrapper.className = `msg ${role}`;

    // 状态行：检索中 / 生成中 / 完成（只对 assistant 显示）
    const status = document.createElement('div');
    status.className = 'msg-status';
    status.style.display = 'none';
    wrapper.appendChild(status);

    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text || '';
    wrapper.appendChild(bubble);

    // 引用来源
    const citations = document.createElement('div');
    citations.className = 'citations';
    wrapper.appendChild(citations);

    // 检索片段详情（可折叠）
    const retrieval = document.createElement('details');
    retrieval.className = 'retrieval';
    retrieval.style.display = 'none';
    wrapper.appendChild(retrieval);

    $('#messages').appendChild(wrapper);
    scrollToBottom();
    return { wrapper, bubble, citations, status, retrieval };
}

function scrollToBottom() {
    const messages = $('#messages');
    messages.scrollTop = messages.scrollHeight;
}

function setStreaming(value) {
    state.streaming = value;
    $('#sendBtn').disabled = value;
}

async function sendQuestion(question) {
    if (state.streaming) return;
    if (!question || !question.trim()) return;

    appendMessage('user', question);
    const assistant = appendMessage('assistant', '正在检索资料...');
    assistant.wrapper.classList.add('typing');
    setStreaming(true);

    // EventSource 只能发 GET，所以把问题放在 query 参数里
    const url = `/api/chat/stream?question=${encodeURIComponent(question)}&kbId=${state.kbId}`;
    const source = new EventSource(url);

    let finished = false;

    // 事件 1：检索完成（带上片段明细）
    source.addEventListener('meta', (event) => {
        const data = JSON.parse(event.data);
        assistant.status.style.display = 'block';
        if (!data.retrievedCount) {
            assistant.status.textContent = '未检索到相关片段';
        } else {
            assistant.status.textContent =
                `已检索到 ${data.retrievedCount} 个相关片段（来自 ${data.documentCount || 0} 个文档）`;
        }
        assistant.bubble.textContent = '';
        renderRetrieval(assistant.retrieval, data.chunks || []);
    });

    // 事件 2：模型逐段返回
    source.addEventListener('delta', (event) => {
        const data = JSON.parse(event.data);
        assistant.bubble.textContent += data.text;
        scrollToBottom();
    });

    // 事件 3：生成结束，带回引用和清洗后的完整回答
    source.addEventListener('done', (event) => {
        const data = JSON.parse(event.data);
        finished = true;
        source.close();
        assistant.wrapper.classList.remove('typing');
        if (typeof data.answer === 'string') {
            assistant.bubble.textContent = data.answer;
        }
        assistant.status.textContent = `回答完成 · ${(data.citations || []).length} 个引用`;
        assistant.status.classList.add('done');
        renderCitations(assistant.citations, data.citations);
        setStreaming(false);
        scrollToBottom();
    });

    // 事件 4：服务端错误（event: error）
    source.addEventListener('error', (event) => {
        if (event.data) {
            try {
                const data = JSON.parse(event.data);
                assistant.status.style.display = 'block';
                assistant.status.textContent = `生成失败：${data.msg || '未知错误'}`;
                assistant.status.classList.add('error');
                assistant.bubble.textContent = '';
            } catch (e) {
                assistant.bubble.textContent = '服务异常';
            }
            finished = true;
            source.close();
            assistant.wrapper.classList.remove('typing');
            setStreaming(false);
        } else if (!finished) {
            // 连接层错误（比如后端没启动）
            assistant.status.style.display = 'block';
            assistant.status.textContent = '连接中断，请确认后端服务是否正常';
            assistant.status.classList.add('error');
            assistant.bubble.textContent = '';
            source.close();
            assistant.wrapper.classList.remove('typing');
            setStreaming(false);
        }
    });
}

// 渲染"检索片段"折叠面板：不用再调 /api/search
function renderRetrieval(container, chunks) {
    container.innerHTML = '';
    if (!chunks || chunks.length === 0) {
        container.style.display = 'none';
        return;
    }
    container.style.display = 'block';

    const summary = document.createElement('summary');
    summary.textContent = `检索片段（${chunks.length}）`;
    container.appendChild(summary);

    const list = document.createElement('div');
    list.className = 'retrieval-list';

    for (const chunk of chunks) {
        const item = document.createElement('div');
        item.className = 'retrieval-item';

        const head = document.createElement('div');
        head.className = 'retrieval-head';
        const page = chunk.pageNo == null ? '页码未知' : `第 ${chunk.pageNo} 页`;
        const score = typeof chunk.score === 'number' ? `相似度 ${(chunk.score * 100).toFixed(1)}%` : '';
        head.textContent = `[${chunk.index}] ${chunk.fileName} · ${page} · ${score}`;
        item.appendChild(head);

        const body = document.createElement('div');
        body.className = 'retrieval-body';
        body.textContent = chunk.snippet || '';
        item.appendChild(body);

        list.appendChild(item);
    }
    container.appendChild(list);
}

function renderCitations(container, citations) {
    container.innerHTML = '';
    if (!citations || citations.length === 0) return;

    for (const citation of citations) {
        const chip = document.createElement('span');
        chip.className = 'cite';
        const page = citation.pageNo == null ? '页码未知' : `第 ${citation.pageNo} 页`;
        chip.textContent = `[${citation.index}] ${citation.fileName} · ${page}`;
        chip.title = citation.snippet || '';
        container.appendChild(chip);
    }
}

// ========== 事件绑定 ==========
function bindEvents() {
    // 选择文件：支持多次追加，选完清空 input 以便重复选同一文件
    $('#fileInput').addEventListener('change', (event) => {
        addSelectedFiles(event.target.files);
        event.target.value = '';
    });

    // 清空已选文件
    $('#clearFiles').addEventListener('click', clearSelectedFiles);

    // 上传
    $('#uploadBtn').addEventListener('click', uploadFiles);

    // 刷新文档列表
    $('#refreshBtn').addEventListener('click', loadDocuments);

    // 知识库 id 变化时重新加载文档
    $('#kbId').addEventListener('change', (event) => {
        state.kbId = Number(event.target.value) || 1;
        loadDocuments();
    });

    // 发送问题
    $('#chatForm').addEventListener('submit', (event) => {
        event.preventDefault();
        const input = $('#question');
        const question = input.value;
        input.value = '';
        sendQuestion(question);
    });

    // Enter 发送，Shift+Enter 换行
    $('#question').addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            $('#chatForm').requestSubmit();
        }
    });
}

// ========== 启动 ==========
document.addEventListener('DOMContentLoaded', async () => {
    bindEvents();
    state.kbId = Number($('#kbId').value) || 1;
    await checkHealth();
    await loadDocuments();
});
