// Nuvio Z - Reaction Mode native stage (experimental, Windows only).
//
// Included at the end of player_bridge.cpp so it shares that translation unit's libmpv loader
// (mpvApi), string helpers and player registry, without a second DLL or a second build step.
// Everything Reaction Mode needs lives here; player_bridge.cpp carries only the #include and the
// small "Reaction Mode seam" block of public accessors on WindowsMpvWebPlayer.
//
// A stage is an independent libmpv instance (vo=gpu-next) playing the reaction video:
//
//   - Popup (dual-screen): mpv renders into a borderless popup this file creates on a thread of
//     its own, on another monitor.
//
//   - In-player (single-screen): mpv is given the main player's *container* as its wid, so its own
//     window is a direct child of the container, like the main player's mpv window. It is stacked at
//     the bottom, and the main player's mpv window is clipped to the movie rectangle with a window
//     region, so the reactions show around the episode and the WebView2 controls stay on top.
//
//     Why this shape (measured 2026-10-13 with Windows Graphics Capture, see STATUS.md): under the
//     transparent WebView2 controls only a D3D surface that is a direct child of the container is
//     composited. A stage window of our own between the controls and the video - created on any
//     thread, stacked anywhere, with or without WS_CLIPSIBLINGS - was never visible; the same mpv
//     window reparented directly under the container was.
//
// mpv runs on its own threads and a stage thread drains its events, so a stalled reaction can never
// block the main player's threads, and vice versa.

namespace nuvio_reaction {

constexpr int kEventFileLoaded = 8;
constexpr int kEventSeek = 20;
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

std::vector<HWND> mpvChildren(HWND parent) {
    std::vector<HWND> found;
    HWND child = nullptr;
    while ((child = FindWindowExW(parent, child, L"mpv", nullptr)) != nullptr) found.push_back(child);
    return found;
}

class Stage : public std::enable_shared_from_this<Stage> {
public:
    // Dual-screen: a popup at (x, y, w, h) in virtual-screen pixels, on a UI thread of its own.
    void startPopup(int x, int y, int w, int h, const std::vector<std::string> &options) {
        auto init = std::make_shared<std::pair<std::mutex, std::condition_variable>>();
        auto done = std::make_shared<bool>(false);
        auto failure = std::make_shared<std::string>();
        auto self = shared_from_this();
        uiThread = std::thread([self, x, y, w, h, options, init, done, failure]() {
            try {
                registerStageClass();
                self->popup = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, kStageWindowClass,
                    L"Nuvio Z Reactions", WS_POPUP | WS_VISIBLE, x, y, std::max(1, w), std::max(1, h),
                    nullptr, nullptr, gModule, self.get());
                if (!self->popup) throw std::runtime_error("Unable to create the reaction stage window.");
                self->createMpv(self->popup, options);
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
        startEventThread();
    }

    // Single-screen: mpv's own window becomes a direct child of the player's container.
    void startInPlayer(HWND playerContainer, const std::vector<std::string> &options) {
        if (!playerContainer || !IsWindow(playerContainer)) throw std::runtime_error("The player has no container window.");
        container = playerContainer;
        // The main player's mpv window already exists; anything new under the container is ours.
        auto existing = mpvChildren(container);
        mainVideo = existing.empty() ? nullptr : existing.front();
        createMpv(container, options);
        startEventThread();
    }

    void shutdown() {
        if (stopping.exchange(true)) return;
        if (popup && IsWindow(popup)) {
            SetWindowPos(popup, nullptr, 0, 0, 0, 0,
                SWP_HIDEWINDOW | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
        }
        clearMainClip();
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (mpv && mpvApi().wakeup) mpvApi().wakeup(mpv);
        }
        WindowsMpvWebPlayer::joinOrDetach(eventThread);
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (mpv) {
                // Destroys mpv's own window too, which is all an in-player stage has.
                mpvApi().terminateDestroy(mpv);
                mpv = nullptr;
            }
        }
        if (popup) PostMessageW(popup, WM_CLOSE, 0, 0);
        if (uiThread.joinable()) WindowsMpvWebPlayer::joinOrDetach(uiThread);
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

    void setPopupBounds(int x, int y, int w, int h) {
        if (!popup) return;
        SetWindowPos(popup, nullptr, x, y, std::max(1, w), std::max(1, h),
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
    }

    void setVisible(bool visible) {
        HWND target = popup ? popup : stageVideo();
        if (!target) return;
        SetWindowPos(target, nullptr, 0, 0, 0, 0,
            (visible ? SWP_SHOWWINDOW : SWP_HIDEWINDOW) | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER |
                SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
    }

    // In-player: the episode is drawn in `movie` (container client pixels); reactions everywhere else.
    // Returns false until mpv has created its window (after the reaction's first frame); call again.
    bool layoutInPlayer(int x, int y, int w, int h) {
        if (!container) return false;
        HWND ours = stageVideo();
        if (!ours) return false;
        SetWindowPos(ours, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
        HWND main = mainVideoWindow();
        if (!main) return false;
        // The system owns the region after a successful SetWindowRgn.
        HRGN region = CreateRectRgn(x, y, x + w, y + h);
        if (!SetWindowRgn(main, region, TRUE)) DeleteObject(region);
        clippedMain = main;
        return true;
    }

    void clearMainClip() {
        HWND main = clippedMain;
        clippedMain = nullptr;
        if (main && IsWindow(main)) SetWindowRgn(main, nullptr, TRUE);
    }

    HWND popup = nullptr;

private:
    HWND container = nullptr;
    HWND mainVideo = nullptr;
    HWND clippedMain = nullptr;
    std::thread uiThread;
    std::thread eventThread;
    std::mutex mpvMutex;
    mpv_handle *mpv = nullptr;
    std::atomic_bool stopping = false;
    std::mutex eventMutex;
    std::deque<std::string> events;

    HWND mainVideoWindow() {
        if (mainVideo && IsWindow(mainVideo)) return mainVideo;
        // The main player had no video window yet when the stage started: it is whichever mpv
        // child of the container is not ours.
        HWND ours = stageVideo();
        for (HWND candidate : mpvChildren(container)) {
            if (candidate != ours) return mainVideo = candidate;
        }
        return nullptr;
    }

    HWND stageVideo() {
        if (!container) return nullptr;
        for (HWND candidate : mpvChildren(container)) {
            if (candidate != mainVideo) return candidate;
        }
        return nullptr;
    }

    void startEventThread() {
        auto self = shared_from_this();
        eventThread = std::thread([self]() { self->drainEvents(); });
    }

    void pushEvent(std::string line) {
        std::lock_guard<std::mutex> lock(eventMutex);
        if (events.size() >= 256) events.pop_front();
        events.push_back(std::move(line));
    }

    void createMpv(HWND wid, const std::vector<std::string> &options) {
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
        int64_t widValue = (int64_t)(intptr_t)wid;
        if (api.setOption(mpv, "wid", MPV_FORMAT_INT64, &widValue) < 0) {
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
            // Safe without the lock: shutdown() stops this thread before destroying the handle.
            mpv_event *event = mpvApi().waitEvent(mpv, 0.25);
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
        case WM_ERASEBKGND: {
            RECT rect = {};
            GetClientRect(hwnd, &rect);
            FillRect((HDC)wParam, &rect, nuvioSurfaceBrush());
            return 1;
        }
        case WM_MOUSEACTIVATE:
            // Never steal focus from the main window.
            return MA_NOACTIVATE;
        case WM_CLOSE:
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);  // the popup's own UI thread
            return 0;
        case WM_NCDESTROY:
            if (stage) stage->popup = nullptr;
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

jlong registerStage(const std::shared_ptr<Stage> &stage) {
    auto *holder = new std::shared_ptr<Stage>(stage);
    jlong handle = (jlong)(intptr_t)holder;
    std::lock_guard<std::mutex> lock(gStagesMutex);
    gStages.insert(handle);
    return handle;
}

} // namespace nuvio_reaction

#define NUVIO_REACTION_JNI(name) Java_com_nuvio_app_features_reaction_ReactionNative_##name

extern "C" JNIEXPORT jlong JNICALL NUVIO_REACTION_JNI(createPopupStage)(
    JNIEnv *env, jobject, jint x, jint y, jint w, jint h, jobjectArray options
) {
    try {
        auto stage = std::make_shared<nuvio_reaction::Stage>();
        stage->startPopup(x, y, w, h, jstringArrayToVector(env, options));
        return nuvio_reaction::registerStage(stage);
    } catch (const std::exception &error) {
        throwJavaError(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL NUVIO_REACTION_JNI(createInPlayerStage)(
    JNIEnv *env, jobject, jlong playerHandle, jobjectArray options
) {
    try {
        auto player = playerFromHandle(playerHandle);
        if (!player) throw std::runtime_error("The player is gone.");
        auto stage = std::make_shared<nuvio_reaction::Stage>();
        stage->startInPlayer(player->reactionContainerHwnd(), jstringArrayToVector(env, options));
        return nuvio_reaction::registerStage(stage);
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

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(stageSetPopupBounds)(JNIEnv *, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    if (auto stage = nuvio_reaction::stageFromHandle(handle)) stage->setPopupBounds(x, y, w, h);
}

extern "C" JNIEXPORT void JNICALL NUVIO_REACTION_JNI(stageSetVisible)(JNIEnv *, jobject, jlong handle, jboolean visible) {
    if (auto stage = nuvio_reaction::stageFromHandle(handle)) stage->setVisible(visible == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL NUVIO_REACTION_JNI(stageLayoutInPlayer)(JNIEnv *, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    auto stage = nuvio_reaction::stageFromHandle(handle);
    return stage && stage->layoutInPlayer(x, y, w, h) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL NUVIO_REACTION_JNI(windowClientSize)(JNIEnv *, jobject, jlong hwndValue) {
    HWND hwnd = (HWND)(intptr_t)hwndValue;
    RECT rect = {};
    if (!hwnd || !IsWindow(hwnd) || !GetClientRect(hwnd, &rect)) return 0;
    return ((jlong)(rect.right - rect.left) << 32) | (jlong)(uint32_t)(rect.bottom - rect.top);
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
