// Nuvio Z - Reaction Mode native stage (experimental, Windows only).
//
// Included at the end of player_bridge.cpp so it shares that translation unit's libmpv loader
// (mpvApi), string helpers and player registry, without a second DLL or a second build step.
// Everything Reaction Mode needs lives here; player_bridge.cpp carries only the #include and the
// small "Reaction Mode seam" block of public accessors on WindowsMpvWebPlayer.
//
// A stage is an independent libmpv instance rendering (vo=gpu-next) into a window this file owns:
//   - a borderless popup on another monitor (dual-screen mode), or
//   - a child window of the main player's container, z-ordered directly below the WebView2
//     controls and above the main video (single-screen mode). A window region with a hole lets the
//     main video show through where the movie is.
// Each stage runs its own UI thread (window + message loop) and its own mpv event thread, so a
// stalled reaction can never block the main player's threads, and vice versa.

namespace nuvio_reaction {

constexpr int kEventFileLoaded = 8;
constexpr int kEventSeek = 20;
constexpr UINT WM_REACTION_TASK = WM_APP + 0x5245;
const wchar_t *kStageWindowClass = L"NuvioReactionStageWindow";

LRESULT CALLBACK stageWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);

void registerStageClass() {
    static std::once_flag once;
    std::call_once(once, []() {
        WNDCLASSEXW wc = {};
        wc.cbSize = sizeof(wc);
        wc.style = CS_HREDRAW | CS_VREDRAW;
        wc.lpfnWndProc = stageWindowProc;
        wc.hInstance = gModule;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.hbrBackground = nullptr;
        wc.lpszClassName = kStageWindowClass;
        RegisterClassExW(&wc);
    });
}

class Stage : public std::enable_shared_from_this<Stage> {
public:
    // parent == nullptr -> popup at (x, y, w, h) in virtual-screen coordinates.
    // parent != nullptr -> child of parent at (x, y, w, h) in parent client coordinates.
    void start(HWND parent, int x, int y, int w, int h, const std::vector<std::string> &options) {
        auto init = std::make_shared<std::pair<std::mutex, std::condition_variable>>();
        auto done = std::make_shared<bool>(false);
        auto failure = std::make_shared<std::string>();
        auto self = shared_from_this();
        uiThread = std::thread([self, parent, x, y, w, h, options, init, done, failure]() {
            try {
                self->createOnUiThread(parent, x, y, w, h, options);
            } catch (const std::exception &error) {
                *failure = error.what();
            }
            {
                std::lock_guard<std::mutex> lock(init->first);
                *done = true;
            }
            init->second.notify_one();
            if (!failure->empty()) return;
            MSG msg = {};
            while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        });
        std::unique_lock<std::mutex> lock(init->first);
        init->second.wait(lock, [&]() { return *done; });
        if (!failure->empty()) {
            lock.unlock();
            if (uiThread.joinable()) uiThread.join();
            throw std::runtime_error(*failure);
        }
        eventThread = std::thread([self]() { self->drainEvents(); });
    }

    void shutdown() {
        if (stopping.exchange(true)) return;
        HWND window = hwnd;
        if (window && IsWindow(window)) {
            SetWindowPos(window, nullptr, 0, 0, 0, 0,
                SWP_HIDEWINDOW | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
        }
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (mpv && mpvApi().wakeup) mpvApi().wakeup(mpv);
        }
        WindowsMpvWebPlayer::joinOrDetach(eventThread);
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (mpv) {
                mpvApi().terminateDestroy(mpv);
                mpv = nullptr;
            }
        }
        if (window) PostMessageW(window, WM_CLOSE, 0, 0);
        WindowsMpvWebPlayer::joinOrDetach(uiThread);
    }

    int command(const std::vector<std::string> &args) {
        if (args.empty()) return -1;
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return -1;
        std::vector<const char *> cargs;
        for (const auto &arg : args) cargs.push_back(arg.c_str());
        cargs.push_back(nullptr);
        return mpvApi().command(mpv, cargs.data());
    }

    int setProperty(const std::string &name, const std::string &value) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return -1;
        return mpvApi().setPropertyString(mpv, name.c_str(), value.c_str());
    }

    bool getProperty(const std::string &name, std::string &out) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return false;
        char *value = nullptr;
        int result = mpvApi().getProperty(mpv, name.c_str(), MPV_FORMAT_STRING, &value);
        if (result < 0 || !value) return false;
        out.assign(value);
        mpvApi().freeValue(value);
        return true;
    }

    // Kotlin polls; one line per event, newest last. Bounded so an unpolled stage cannot grow.
    std::string takeEvents() {
        std::lock_guard<std::mutex> lock(eventMutex);
        std::string joined;
        for (const auto &line : events) {
            joined += line;
            joined.push_back('\n');
        }
        events.clear();
        return joined;
    }

    void setBounds(int x, int y, int w, int h) {
        if (!hwnd) return;
        SetWindowPos(hwnd, nullptr, x, y, std::max(1, w), std::max(1, h),
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
    }

    void setVisible(bool visible) {
        if (!hwnd) return;
        SetWindowPos(hwnd, nullptr, 0, 0, 0, 0,
            (visible ? SWP_SHOWWINDOW : SWP_HIDEWINDOW) | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER |
                SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
    }

    // A hole (in client coordinates) the main video shows through. w <= 0 removes it.
    void setHole(int x, int y, int w, int h) {
        postTask([self = shared_from_this(), x, y, w, h]() {
            if (!self->hwnd) return;
            if (w <= 0 || h <= 0) {
                SetWindowRgn(self->hwnd, nullptr, TRUE);
                return;
            }
            RECT client = {};
            GetClientRect(self->hwnd, &client);
            HRGN full = CreateRectRgn(0, 0, client.right, client.bottom);
            HRGN hole = CreateRectRgn(x, y, x + w, y + h);
            CombineRgn(full, full, hole, RGN_DIFF);
            DeleteObject(hole);
            // The system owns the region after a successful SetWindowRgn.
            if (!SetWindowRgn(self->hwnd, full, TRUE)) DeleteObject(full);
        });
    }

    // Single-screen mode: keep the stage directly below the WebView2 controls, whatever order the
    // container's children were created in. Called after start and again when asked.
    void restack() {
        postTask([self = shared_from_this()]() {
            HWND parent = GetParent(self->hwnd);
            if (!parent) return;
            HWND web = FindWindowExW(parent, nullptr, L"Chrome_WidgetWin_0", nullptr);
            // SetWindowPos(hwnd, after) places hwnd *behind* `after`: just under the controls.
            SetWindowPos(self->hwnd, web ? web : HWND_TOP, 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        });
    }

    long long nativeHandle() const { return (long long)(intptr_t)hwnd; }

    void processTasks() {
        std::deque<std::function<void()>> pending;
        {
            std::lock_guard<std::mutex> lock(taskMutex);
            pending.swap(tasks);
        }
        for (auto &task : pending) task();
    }

    HWND hwnd = nullptr;

private:
    std::thread uiThread;
    std::thread eventThread;
    std::mutex mpvMutex;
    mpv_handle *mpv = nullptr;
    std::atomic_bool stopping = false;
    std::mutex eventMutex;
    std::deque<std::string> events;
    std::mutex taskMutex;
    std::deque<std::function<void()>> tasks;

    void postTask(std::function<void()> task) {
        if (stopping.load() || !hwnd) return;
        {
            std::lock_guard<std::mutex> lock(taskMutex);
            tasks.push_back(std::move(task));
        }
        PostMessageW(hwnd, WM_REACTION_TASK, 0, 0);
    }

    void pushEvent(std::string line) {
        std::lock_guard<std::mutex> lock(eventMutex);
        if (events.size() >= 256) events.pop_front();
        events.push_back(std::move(line));
    }

    void createOnUiThread(HWND parent, int x, int y, int w, int h, const std::vector<std::string> &options) {
        registerStageClass();
        DWORD style = parent ? (WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS) : (WS_POPUP | WS_VISIBLE);
        DWORD exStyle = parent ? 0 : (WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE);
        hwnd = CreateWindowExW(exStyle, kStageWindowClass, L"Nuvio Z Reactions", style,
            x, y, std::max(1, w), std::max(1, h), parent, nullptr, gModule, this);
        if (!hwnd) throw std::runtime_error("Unable to create the reaction stage window.");

        MpvApi &api = mpvApi();
        std::lock_guard<std::mutex> lock(mpvMutex);
        mpv = api.create();
        if (!mpv) throw std::runtime_error("mpv_create failed for the reaction stage.");
        api.setOptionString(mpv, "config", "no");
        api.setOptionString(mpv, "osc", "no");
        api.setOptionString(mpv, "input-default-bindings", "no");
        api.setOptionString(mpv, "input-vo-keyboard", "no");
        api.setOptionString(mpv, "input-cursor", "no");
        api.setOptionString(mpv, "keep-open", "yes");
        api.setOptionString(mpv, "idle", "yes");
        api.setOptionString(mpv, "vo", "gpu-next");
        api.setOptionString(mpv, "hwdec", "auto");
        api.setOptionString(mpv, "keepaspect", "no");
        api.setOptionString(mpv, "background-color", "#0e0f13");
        // Caller-supplied options last, so Kotlin can override any default above.
        for (const std::string &option : options) {
            size_t eq = option.find('=');
            if (eq == std::string::npos) continue;
            api.setOptionString(mpv, option.substr(0, eq).c_str(), option.substr(eq + 1).c_str());
        }
        int64_t wid = (int64_t)(intptr_t)hwnd;
        if (api.setOption(mpv, "wid", MPV_FORMAT_INT64, &wid) < 0) {
            throw std::runtime_error("mpv wid option failed for the reaction stage.");
        }
        int initResult = api.initialize(mpv);
        if (initResult < 0) {
            throw std::runtime_error(std::string("mpv_initialize failed for the reaction stage: ") +
                (api.errorString ? api.errorString(initResult) : "unknown"));
        }
        if (api.requestLogMessages) api.requestLogMessages(mpv, "warn");
    }

    void drainEvents() {
        while (!stopping.load()) {
            mpv_event *event = nullptr;
            {
                // mpv_wait_event is documented as safe alongside other API calls on the same handle,
                // but the handle itself is destroyed under mpvMutex, so read it under the lock.
                std::lock_guard<std::mutex> lock(mpvMutex);
                if (!mpv) return;
            }
            event = mpvApi().waitEvent(mpv, 0.25);
            if (!event || event->event_id == MPV_EVENT_NONE) continue;
            switch ((int)event->event_id) {
                case MPV_EVENT_SHUTDOWN:
                    pushEvent("shutdown");
                    return;
                case MPV_EVENT_END_FILE: {
                    auto *end = static_cast<mpv_event_end_file *>(event->data);
                    pushEvent("end-file " + std::to_string(end ? end->reason : -1) + " " +
                        std::to_string(end ? end->error : 0));
                    break;
                }
                case kEventFileLoaded:
                    pushEvent("file-loaded");
                    break;
                case kEventSeek:
                    pushEvent("seek");
                    break;
                case MPV_EVENT_PLAYBACK_RESTART:
                    pushEvent("playback-restart");
                    break;
                case MPV_EVENT_LOG_MESSAGE: {
                    auto *log = static_cast<mpv_event_log_message *>(event->data);
                    if (log && log->text) {
                        std::string text = std::string("log ") + (log->level ? log->level : "?") + " " +
                            (log->prefix ? log->prefix : "?") + ": " + log->text;
                        while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
                        pushEvent(text);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }
};

LRESULT CALLBACK stageWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_NCCREATE) {
        auto *create = reinterpret_cast<CREATESTRUCTW *>(lParam);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(create->lpCreateParams));
        return DefWindowProcW(hwnd, message, wParam, lParam);
    }
    auto *stage = reinterpret_cast<Stage *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (message) {
        case WM_REACTION_TASK:
            if (stage) stage->processTasks();
            return 0;
        case WM_ERASEBKGND: {
            RECT rect = {};
            GetClientRect(hwnd, &rect);
            FillRect((HDC)wParam, &rect, nuvioSurfaceBrush());
            return 1;
        }
        case WM_MOUSEACTIVATE:
            // Never steal focus from the main window, whichever mode the stage is in.
            return MA_NOACTIVATE;
        case WM_CLOSE:
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
        case WM_NCDESTROY:
            if (stage) stage->hwnd = nullptr;
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
            return DefWindowProcW(hwnd, message, wParam, lParam);
        default:
            return DefWindowProcW(hwnd, message, wParam, lParam);
    }
}

std::mutex gStagesMutex;
std::unordered_set<jlong> gStages;

std::shared_ptr<Stage> stageFromHandle(jlong handle) {
    if (handle == 0) return nullptr;
    std::lock_guard<std::mutex> lock(gStagesMutex);
    if (gStages.find(handle) == gStages.end()) return nullptr;
    return *reinterpret_cast<std::shared_ptr<Stage> *>(handle);
}

} // namespace nuvio_reaction

#define NUVIO_REACTION_JNI(name) Java_com_nuvio_app_features_reaction_ReactionNative_##name

extern "C" JNIEXPORT jlong JNICALL NUVIO_REACTION_JNI(createStage)(
    JNIEnv *env, jobject, jlong parentHwnd, jint x, jint y, jint w, jint h, jobjectArray options
) {
    try {
        auto stage = std::make_shared<nuvio_reaction::Stage>();
        stage->start((HWND)(intptr_t)parentHwnd, x, y, w, h, jstringArrayToVector(env, options));
        if (parentHwnd) stage->restack();
        auto *holder = new std::shared_ptr<nuvio_reaction::Stage>(stage);
        jlong handle = (jlong)(intptr_t)holder;
        std::lock_guard<std::mutex> lock(nuvio_reaction::gStagesMutex);
        nuvio_reaction::gStages.insert(handle);
        return handle;
    } catch (const std::exception &error) {
        throwJavaError(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(destroyStage)(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<nuvio_reaction::Stage> stage;
    {
        std::lock_guard<std::mutex> lock(nuvio_reaction::gStagesMutex);
        if (nuvio_reaction::gStages.erase(handle) == 0) return;
        auto *holder = reinterpret_cast<std::shared_ptr<nuvio_reaction::Stage> *>(handle);
        stage = *holder;
        delete holder;
    }
    stage->shutdown();
}

extern "C" JNIEXPORT jint JNICALL NUVIO_REACTION_JNI(stageCommand)(JNIEnv *env, jobject, jlong handle, jobjectArray args) {
    auto stage = nuvio_reaction::stageFromHandle(handle);
    return stage ? stage->command(jstringArrayToVector(env, args)) : -1;
}

extern "C" JNIEXPORT jint JNICALL NUVIO_REACTION_JNI(stageSetProperty)(JNIEnv *env, jobject, jlong handle, jstring name, jstring value) {
    auto stage = nuvio_reaction::stageFromHandle(handle);
    return stage ? stage->setProperty(jstringToUtf8(env, name), jstringToUtf8(env, value)) : -1;
}

extern "C" JNIEXPORT jstring JNICALL NUVIO_REACTION_JNI(stageGetProperty)(JNIEnv *env, jobject, jlong handle, jstring name) {
    auto stage = nuvio_reaction::stageFromHandle(handle);
    std::string value;
    if (!stage || !stage->getProperty(jstringToUtf8(env, name), value)) return nullptr;
    return newJavaStringUtf8(env, value);
}

extern "C" JNIEXPORT jstring JNICALL NUVIO_REACTION_JNI(stageTakeEvents)(JNIEnv *env, jobject, jlong handle) {
    auto stage = nuvio_reaction::stageFromHandle(handle);
    return newJavaStringUtf8(env, stage ? stage->takeEvents() : std::string());
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(stageSetBounds)(JNIEnv *, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    if (auto stage = nuvio_reaction::stageFromHandle(handle)) stage->setBounds(x, y, w, h);
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(stageSetVisible)(JNIEnv *, jobject, jlong handle, jboolean visible) {
    if (auto stage = nuvio_reaction::stageFromHandle(handle)) stage->setVisible(visible == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(stageSetHole)(JNIEnv *, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    if (auto stage = nuvio_reaction::stageFromHandle(handle)) stage->setHole(x, y, w, h);
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(stageRestack)(JNIEnv *, jobject, jlong handle) {
    if (auto stage = nuvio_reaction::stageFromHandle(handle)) stage->restack();
}

// --- The main player, through the "Reaction Mode seam" accessors on WindowsMpvWebPlayer. ---

extern "C" JNIEXPORT jlong JNICALL NUVIO_REACTION_JNI(mainContainerHwnd)(JNIEnv *, jobject, jlong playerHandle) {
    auto player = playerFromHandle(playerHandle);
    return player ? (jlong)(intptr_t)player->reactionContainerHwnd() : 0;
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(mainSetProperty)(JNIEnv *env, jobject, jlong playerHandle, jstring name, jstring value) {
    if (auto player = playerFromHandle(playerHandle)) {
        player->reactionSetProperty(jstringToUtf8(env, name), jstringToUtf8(env, value));
    }
}

extern "C" JNIEXPORT jstring JNICALL NUVIO_REACTION_JNI(mainGetProperty)(JNIEnv *env, jobject, jlong playerHandle, jstring name) {
    auto player = playerFromHandle(playerHandle);
    if (!player) return nullptr;
    return newJavaStringUtf8(env, player->reactionGetProperty(jstringToUtf8(env, name)));
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(mainRunControlsScript)(JNIEnv *env, jobject, jlong playerHandle, jstring script) {
    if (auto player = playerFromHandle(playerHandle)) {
        player->reactionRunControlsScript(jstringToUtf8(env, script));
    }
}

#undef NUVIO_REACTION_JNI
