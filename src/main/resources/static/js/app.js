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
        item.appendChild(actions);

        container.appendChild(item);
    }
}

function formatTime(value) {
    if (!value) return '';
    const date = new Date(value);
    return isNaN(date.getTime()) ? '' : date.toLocaleString();
}

// ========== 上传 ==========
async function uploadFile() {
    const fileInput = $('#fileInput');
    const hint = $('#uploadHint');
    const button = $('#uploadBtn');

    if (!fileInput.files || fileInput.files.length === 0) {
        hint.textContent = '请先选择一个文件。';
        return;
    }

    const file = fileInput.files[0];
    const formData = new FormData();
    formData.append('kbId', String(state.kbId));
    formData.append('file', file);

    button.disabled = true;
    hint.textContent = `正在上传 ${file.name} ...`;

    try {
        const result = await api('/api/documents', { method: 'POST', body: formData });
        hint.textContent = `上传成功（文档 ID ${result.id}），正在解析、切片、向量化...`;
        fileInput.value = '';
        // 轮询解析状态，最多约 30 秒
        await pollDocumentStatus(result.id);
        await loadDocuments();
        hint.textContent = '索引完成，可以开始提问了。';
    } catch (e) {
        hint.textContent = `上传失败：${e.message}`;
    } finally {
        button.disabled = false;
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

// ========== 聊天 ==========
function appendMessage(role, text) {
    // 第一次发消息时移除空状态提示
    const emptyTip = $('.empty-tip');
    if (emptyTip) emptyTip.remove();

    const wrapper = document.createElement('div');
    wrapper.className = `msg ${role}`;

    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text || '';
    wrapper.appendChild(bubble);

    const citations = document.createElement('div');
    citations.className = 'citations';
    wrapper.appendChild(citations);

    $('#messages').appendChild(wrapper);
    scrollToBottom();
    return { wrapper, bubble, citations };
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

    // 事件 1：检索完成
    source.addEventListener('meta', (event) => {
        const data = JSON.parse(event.data);
        assistant.bubble.textContent = `已检索到 ${data.retrievedCount} 个相关片段，正在生成回答...\n`;
    });

    // 事件 2：模型逐段返回
    source.addEventListener('delta', (event) => {
        const data = JSON.parse(event.data);
        assistant.bubble.textContent += data.text;
        scrollToBottom();
    });

    // 事件 3：生成结束，带回引用
    source.addEventListener('done', (event) => {
        const data = JSON.parse(event.data);
        finished = true;
        source.close();
        assistant.wrapper.classList.remove('typing');
        renderCitations(assistant.citations, data.citations);
        setStreaming(false);
        scrollToBottom();
    });

    // 事件 4：服务端错误（event: error）
    source.addEventListener('error', (event) => {
        if (event.data) {
            try {
                const data = JSON.parse(event.data);
                assistant.bubble.textContent = `服务异常：${data.msg || '未知错误'}`;
            } catch (e) {
                assistant.bubble.textContent = '服务异常';
            }
            finished = true;
            source.close();
            assistant.wrapper.classList.remove('typing');
            setStreaming(false);
        } else if (!finished) {
            // 连接层错误（比如后端没启动）
            assistant.bubble.textContent = '连接中断，请确认后端服务是否正常。';
            source.close();
            assistant.wrapper.classList.remove('typing');
            setStreaming(false);
        }
    });
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
    // 上传
    $('#uploadBtn').addEventListener('click', uploadFile);

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
