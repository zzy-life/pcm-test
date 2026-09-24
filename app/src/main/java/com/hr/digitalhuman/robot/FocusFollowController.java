package com.hr.digitalhuman.robot;

import android.os.Handler;
import android.os.Looper;

import com.ainirobot.coreservice.client.Definition;
import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.listener.ActionListener;
import com.ainirobot.coreservice.client.listener.Person;
import com.ainirobot.coreservice.client.person.PersonApi;
import com.ainirobot.coreservice.client.person.PersonListener;
import com.ainirobot.coreservice.client.person.PersonUtils;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.RobotConfig;

import java.util.List;

/**
 * Standby focus-follow: when a guest approaches, robot tracks with head + chassis
 * via RobotApi.startFocusFollow, then triggers welcome when close enough.
 */
public class FocusFollowController {

    private static final String TAG = "FocusFollowController";
    private static final int REQ_ID = 1001;
    private static final int SMART_REQ_ID = 1002;
    private static final float DEFAULT_WELCOME_DIST_M = 1.5f;
    private static final float DEFAULT_WELCOME_ANGLE = 45f;
    private static final long SCAN_INTERVAL_MS = 500L;

    public interface Callback {
        void onFollowingChanged(boolean following);

        void onPersonReadyForWelcome(String personId);

        /** 导航等底盘动作进行中时不能 startFocusFollow。 */
        boolean isChassisBusy();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback callback;
    private boolean active;
    private boolean following;
    /** 仅当 startFocusFollow 已被系统接受时才允许 stop，否则 SDK 会因 ActionInfo 为空而崩溃。 */
    private boolean followAccepted;
    private boolean startingFollow;
    /** 已经对这个 faceId 调用过 startFocusFollow。丢失或主动停止前不再调用。 */
    private int issuedFaceId = -1;
    private long nextStartAllowedAt;
    private boolean releasedLeftoverSmart;
    private static final double MOVE_ANGLE_X = 22.0;
    private static final double MOVE_ANGLE_Y = 16.0;
    private static final double MOVE_DISTANCE_M = 0.40;
    private static final int MOVE_BOX_PX = 100;
    /** 人脸丢帧后这么久才停；距离明显变远则立刻停，不等这个宽限。 */
    private static final long ABSENT_STOP_MS = 600L;
    private static final long SETTLE_STOP_MS = 2000L;
    /** 人脸框过小视为实际很远，即使测距报成近场也不跟。 */
    private static final int MIN_START_FACE_PX = 48;
    private static final int MIN_KEEP_FACE_PX = 32;
    private long lastWaitLogAt;
    private int followingFaceId = -1;
    private long approachSince = 0L;
    private Runnable approachRunnable;
    private Runnable scanRunnable;
    private boolean scanQueued;
    private PersonListener personListener;
    private boolean welcomeSignaled;
    /** 迎宾播报中暂停跟随，避免动作抢走 TTS。 */
    private boolean holdFollowForWelcome;
    private int pendingWelcomePersonId = -1;
    /** 上一帧人脸位姿。用来判断是挪动了，还是彻底消失。 */
    private FaceSample faceAnchor;
    /** 跟随过程中用来判断人是否还在大幅移动。 */
    private FaceSample followMotionAnchor;
    private long absentSince;
    private long stillSince;
    private long followStartedAt;

    public void start(Callback callback) {
        this.callback = callback;
        releaseLeftoverSmartFollow();
        if (!active) {
            active = true;
            following = false;
            followAccepted = false;
            startingFollow = false;
            followingFaceId = -1;
            issuedFaceId = -1;
            welcomeSignaled = false;
            holdFollowForWelcome = false;
            pendingWelcomePersonId = -1;
            faceAnchor = null;
            followMotionAnchor = null;
            absentSince = 0L;
            stillSince = 0L;
            registerPersonListener();
            startPeriodicScan();
        }
        scanForPerson("start");
    }

    public void stop() {
        active = false;
        stopPeriodicScan();
        unregisterPersonListener();
        cancelApproachCheck();
        stopSdkFollowIfAccepted();
        if (following) {
            following = false;
            notifyFollowing(false);
        }
        followingFaceId = -1;
        issuedFaceId = -1;
        nextStartAllowedAt = 0L;
        welcomeSignaled = false;
        holdFollowForWelcome = false;
        pendingWelcomePersonId = -1;
        faceAnchor = null;
        followMotionAnchor = null;
        absentSince = 0L;
        stillSince = 0L;
    }

    /** 回到待机后允许下一位来宾再次迎宾。 */
    public void resetWelcomeCycle() {
        welcomeSignaled = false;
        holdFollowForWelcome = false;
        cancelApproachCheck();
    }

    /** 欢迎语说完后再恢复焦点跟随。 */
    public void releaseWelcomeSpeechHold() {
        holdFollowForWelcome = false;
    }

    public boolean isFollowing() {
        return following;
    }

    private void registerPersonListener() {
        if (personListener != null) {
            return;
        }
        personListener = new PersonListener() {
            @Override
            public void personChanged() {
                // 文档写明这是毫秒级回调，合并成一次扫描，避免连打 startFocusFollow
                if (scanQueued) {
                    return;
                }
                scanQueued = true;
                mainHandler.post(() -> {
                    scanQueued = false;
                    scanForPerson("personChanged");
                });
            }
        };
        try {
            PersonApi.getInstance().registerPersonListener(personListener);
        } catch (Throwable t) {
            DebugLog.e(TAG, "registerPersonListener failed", t);
        }
    }

    private void unregisterPersonListener() {
        if (personListener == null) {
            return;
        }
        try {
            PersonApi.getInstance().unregisterPersonListener(personListener);
        } catch (Throwable ignored) {
        }
        personListener = null;
    }

    private void startPeriodicScan() {
        stopPeriodicScan();
        scanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!active) {
                    return;
                }
                scanForPerson("periodic");
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(scanRunnable, SCAN_INTERVAL_MS);
    }

    private void stopPeriodicScan() {
        if (scanRunnable != null) {
            mainHandler.removeCallbacks(scanRunnable);
            scanRunnable = null;
        }
    }

    private void scanForPerson(String source) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> scanForPerson(source));
            return;
        }
        if (!active) {
            return;
        }
        RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        if (config == null) {
            return;
        }
        RobotConfig.FocusFollowConfig ff = config.focusFollow != null
                ? config.focusFollow : new RobotConfig.FocusFollowConfig();
        RobotSdkBridge bridge = DigitalHumanApp.getInstance().getRobotBridge();
        if (bridge == null || !bridge.isRobotApiConnected()) {
            return;
        }

        try {
            // 迎宾只触发一次；焦点跟随在迎宾之后仍要继续，不能被 welcomeSignaled 挡住
            if (!welcomeSignaled) {
                checkApproachForWelcome(source, ff);
            }

            if (!ff.enabled) {
                if ("start".equals(source)) {
                    DebugLog.w(TAG, "focus follow disabled in config");
                }
                return;
            }

            float detectDist = resolveFollowDistance(ff);
            if (following) {
                watchRunningFollow(detectDist);
                return;
            }
            if (System.currentTimeMillis() < nextStartAllowedAt) {
                return;
            }
            if (callback != null && callback.isChassisBusy()) {
                if ("start".equals(source)) {
                    DebugLog.w(TAG, "skip focus follow: chassis busy");
                }
                return;
            }
            // 文档：faceId 必须是完整人脸（id >= 0）。不完整人脸不能拿去 startFocusFollow。
            Person best = pickTrackableFace(detectDist);
            if (best == null || !isFollowableNearFace(best, detectDist, true)) {
                if (!following) {
                    issuedFaceId = -1;
                    faceAnchor = null;
                }
                logWaitingForCompleteFace(source);
                return;
            }
            if (best.getId() == issuedFaceId) {
                return;
            }
            FaceSample now = FaceSample.capture(best);
            if (now == null) {
                faceAnchor = null;
                return;
            }
            // 头部正在转时，角度变化来自云台，不当成人移动
            if (now.headSpeed != 0) {
                faceAnchor = now;
                return;
            }
            if (now.latency > 800L) {
                return;
            }
            if (faceAnchor == null || faceAnchor.id != now.id) {
                faceAnchor = now;
                DebugLog.i(TAG, "face baseline id=" + now.id
                        + " d=" + String.format("%.2f", now.distance)
                        + " ax=" + String.format("%.1f", now.faceAngleX)
                        + " ay=" + String.format("%.1f", now.faceAngleY));
                return;
            }
            if (!movedSignificantly(faceAnchor, now)) {
                return;
            }
            DebugLog.i(TAG, "face moved id=" + now.id
                    + " dd=" + String.format("%.2f", Math.abs(now.distance - faceAnchor.distance))
                    + " dAx=" + String.format("%.1f", Math.abs(now.faceAngleX - faceAnchor.faceAngleX))
                    + " dAy=" + String.format("%.1f", Math.abs(now.faceAngleY - faceAnchor.faceAngleY))
                    + " dFace=" + faceShift(faceAnchor, now)
                    + " dBody=" + bodyShift(faceAnchor, now));
            faceAnchor = now;
            startFocusFollowForPerson(best, ff);
            if (following) {
                followMotionAnchor = now;
                stillSince = 0L;
                absentSince = 0L;
                followStartedAt = System.currentTimeMillis();
            }
        } catch (Throwable t) {
            DebugLog.e(TAG, "scanForPerson error (" + source + ")", t);
        }
    }

    /**
     * 跟随中继续看人脸。人脸离开或距离变远，立刻 stopFocusFollow。
     * 不能一直等系统丢失回调，否则人走了底盘还在转。
     */
    private void watchRunningFollow(float detectDist) {
        Person tracked = findTrackedPersonById();
        long now = System.currentTimeMillis();
        if (tracked == null || !hasVisibleFace(tracked)) {
            stillSince = 0L;
            if (absentSince == 0L) {
                absentSince = now;
            }
            if (now - absentSince >= ABSENT_STOP_MS) {
                DebugLog.i(TAG, "stop focus follow: face left");
                stopRunningFollow();
            }
            return;
        }
        if (isTooFarForFollow(tracked, detectDist, false)) {
            DebugLog.i(TAG, "stop focus follow: too far d="
                    + String.format("%.2f", tracked.getDistance())
                    + " face=" + tracked.getFaceWidth() + "x" + tracked.getFaceHeight());
            stopRunningFollow();
            return;
        }
        absentSince = 0L;
        FaceSample sample = FaceSample.capture(tracked);
        if (sample == null || sample.latency > 800L) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        // 刚启动时先让底盘转起来。「跟随成功」只表示锁定目标，不是转完。
        if (followStartedAt > 0L && nowMs - followStartedAt < 600L) {
            return;
        }
        // 已经正对人，头也停了：跟随完成，立刻停。垂直角受摄像头安装影响，不作为完成条件。
        if (sample.headSpeed == 0 && Math.abs(sample.faceAngleX) <= 10.0) {
            DebugLog.i(TAG, "stop focus follow: facing person ax="
                    + String.format("%.1f", sample.faceAngleX));
            stopRunningFollow();
            return;
        }
        if (followMotionAnchor == null || followMotionAnchor.id != sample.id) {
            followMotionAnchor = sample;
            return;
        }
        if (movedSignificantly(followMotionAnchor, sample, sample.headSpeed != 0)) {
            followMotionAnchor = sample;
        }
    }

    private Person findTrackedPersonById() {
        int faceId = followingFaceId >= 0 ? followingFaceId : issuedFaceId;
        if (faceId < 0) {
            return null;
        }
        Person found = findPersonById(PersonApi.getInstance().getAllFaceList(), faceId);
        if (found == null) {
            found = findPersonById(PersonApi.getInstance().getCompleteFaceList(), faceId);
        }
        if (found == null) {
            found = findPersonById(PersonApi.getInstance().getAllPersons(), faceId);
        }
        return found;
    }

    private void stopRunningFollow() {
        stopSdkFollowIfAccepted();
        releaseFollowLock(2000L);
    }

    /**
     * 直接按「正前方近场」判断迎宾，避免远处人影/侧身身体误唤醒。
     */
    private void checkApproachForWelcome(String source, RobotConfig.FocusFollowConfig cfg) {
        Person nearest = pickFrontNearForWelcome(cfg);
        if (nearest == null) {
            cancelApproachCheck();
            return;
        }
        scheduleWelcomeCheck(nearest.getId(), cfg);
    }

    private float welcomeDistance(RobotConfig.FocusFollowConfig cfg) {
        float d = cfg.welcomeDistanceM > 0 ? cfg.welcomeDistanceM : DEFAULT_WELCOME_DIST_M;
        // 近场迎宾：允许到约 1.8m，避免过严导致站在面前也不唤醒
        return Math.min(Math.max(d, 0.8f), 1.8f);
    }

    /**
     * 焦点跟随只允许近场人脸。超出检测距离（默认 1.5 米，最高 1.8 米）一律不启动、也不继续跟。
     */
    private float resolveFollowDistance(RobotConfig.FocusFollowConfig cfg) {
        float d = 1.5f;
        if (cfg != null && cfg.detectDistanceM > 0) {
            d = cfg.detectDistanceM;
        }
        if (d < 1.2f) {
            d = 1.2f;
        }
        if (d > 1.8f) {
            d = 1.8f;
        }
        return d;
    }

    private float welcomeAngle(RobotConfig.FocusFollowConfig cfg) {
        if (cfg.welcomeMaxFaceAngleX > 0) {
            return Math.min(cfg.welcomeMaxFaceAngleX, 50f);
        }
        return DEFAULT_WELCOME_ANGLE;
    }

    /** 正前方近场：优先完整人脸；过近时允许居中身体兜底 */
    private Person pickFrontNearForWelcome(RobotConfig.FocusFollowConfig cfg) {
        float maxDist = welcomeDistance(cfg);
        float maxAngle = welcomeAngle(cfg);

        Person face = PersonUtils.getBestFace(
                PersonApi.getInstance().getAllFaceList(), maxDist, maxAngle);
        if (face == null) {
            face = PersonUtils.getBestFace(
                    PersonApi.getInstance().getCompleteFaceList(), maxDist, maxAngle);
        }
        if (face != null && isFrontNearFace(face, maxDist, maxAngle)) {
            return face;
        }

        List<Person> faces = PersonApi.getInstance().getAllFaceList();
        Person bestFace = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        if (faces != null) {
            for (Person p : faces) {
                if (p == null || p.getId() < 0 || p.getFaceWidth() <= 0
                        || !isFrontNearFace(p, maxDist, maxAngle)) {
                    continue;
                }
                double ax = Math.abs(faceYaw(p));
                double score = (maxDist - p.getDistance()) * 2.0
                        + (maxAngle - ax) / Math.max(1.0, maxAngle);
                if (score > bestScore) {
                    bestScore = score;
                    bestFace = p;
                }
            }
        }
        if (bestFace != null) {
            return bestFace;
        }

        // 人脸偶发漏检：1.0m 内居中身体也可迎宾（侧后方仍排除）
        float bodyDist = Math.min(maxDist, 1.0f);
        Person body = PersonUtils.getBestBody(
                PersonApi.getInstance().getAllBodyList(), bodyDist);
        if (body != null && isFrontNearBody(body, bodyDist, maxAngle)) {
            return body;
        }
        if (!cfg.welcomeRequireFace) {
            List<Person> bodies = PersonApi.getInstance().getAllBodyList();
            if (bodies != null) {
                for (Person p : bodies) {
                    if (p != null && isFrontNearBody(p, bodyDist, maxAngle)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** 人脸迎宾：用 faceAngleX（朝向机器人），不用 angleInView（易与声源/身体方位混淆导致误杀） */
    private boolean isFrontNearFace(Person p, float maxDist, float maxAngle) {
        if (p == null) {
            return false;
        }
        double d = p.getDistance();
        if (d <= 0 || d > maxDist) {
            return false;
        }
        return Math.abs(faceYaw(p)) <= maxAngle;
    }

    private boolean isFrontNearBody(Person p, float maxDist, float maxAngle) {
        if (p == null) {
            return false;
        }
        double d = p.getDistance();
        if (d <= 0 || d > maxDist) {
            return false;
        }
        return Math.abs(bodyYaw(p)) <= maxAngle;
    }

    private static double faceYaw(Person p) {
        try {
            return p.getFaceAngleX();
        } catch (Throwable t) {
            return 999;
        }
    }

    private static double bodyYaw(Person p) {
        try {
            double view = p.getAngleInView();
            if (Math.abs(view) > 0.01) {
                return view;
            }
        } catch (Throwable ignored) {
        }
        try {
            return p.getAngle();
        } catch (Throwable t) {
            return 999;
        }
    }

    private boolean isFrontNear(Person p, float maxDist, float maxAngle) {
        if (p == null) {
            return false;
        }
        try {
            if (PersonUtils.isFace(p) || PersonUtils.isCompleteFace(p)) {
                return isFrontNearFace(p, maxDist, maxAngle);
            }
        } catch (Throwable ignored) {
        }
        return isFrontNearBody(p, maxDist, maxAngle);
    }

    /** 画面里还有人脸框。没框就当人脸已经离开，不能靠残留距离继续跟。 */
    private static boolean hasVisibleFace(Person person) {
        if (person == null || person.getId() < 0) {
            return false;
        }
        return person.getFaceWidth() > 0 || person.getFaceHeight() > 0;
    }

    private boolean isFollowableNearFace(Person person, float maxDistanceM, boolean starting) {
        if (!hasVisibleFace(person)) {
            return false;
        }
        return !isTooFarForFollow(person, maxDistanceM, starting);
    }

    /**
     * 距离超过近场，或人脸框过小（测距把远处的人报近），都不允许跟随。
     */
    private static boolean isTooFarForFollow(Person person, float maxDistanceM, boolean starting) {
        if (person == null) {
            return true;
        }
        double d = 0;
        try {
            d = person.getDistance();
        } catch (Throwable ignored) {
        }
        if (d > maxDistanceM) {
            return true;
        }
        if (starting && (d <= 0 || d > maxDistanceM)) {
            return true;
        }
        int minPx = starting ? MIN_START_FACE_PX : MIN_KEEP_FACE_PX;
        int w = 0;
        int h = 0;
        try {
            w = person.getFaceWidth();
            h = person.getFaceHeight();
        } catch (Throwable ignored) {
        }
        if (w > 0 && w < minPx && h > 0 && h < minPx) {
            return true;
        }
        return false;
    }

    /**
     * 文档：id >= 0 即可作为 startFocusFollow 的 faceId。
     * 不要用 PersonUtils.isFace：它等于 with_face 且 liveNess!=0，本机不上报活体，会把 id=0/1 全部丢掉。
     */
    private Person pickTrackableFace(float maxDistanceM) {
        Person best = nearestVisibleFace(PersonApi.getInstance().getAllFaceList(), maxDistanceM);
        if (best == null) {
            best = nearestVisibleFace(PersonApi.getInstance().getCompleteFaceList(), maxDistanceM);
        }
        return best;
    }

    /** id >= 0、有人脸框，并且在近场。远处或已离开的脸不能拿去跟随。 */
    private static boolean isTrackableFace(Person person, float maxDistanceM) {
        if (person == null || person.getId() < 0) {
            return false;
        }
        if (!hasVisibleFace(person)) {
            return false;
        }
        return !isTooFarForFollow(person, maxDistanceM, true);
    }

    private static Person nearestVisibleFace(List<Person> faces, float maxDistanceM) {
        if (faces == null) {
            return null;
        }
        Person near = null;
        for (Person p : faces) {
            if (!isTrackableFace(p, maxDistanceM)) {
                continue;
            }
            if (near == null || preferFace(p, near)) {
                near = p;
            }
        }
        return near;
    }

    /** 优先非 other_face，其次更近。 */
    private static boolean preferFace(Person candidate, Person current) {
        try {
            if (candidate.isOtherFace() != current.isOtherFace()) {
                return !candidate.isOtherFace();
            }
        } catch (Throwable ignored) {
        }
        return candidate.getDistance() < current.getDistance();
    }

    private void logWaitingForCompleteFace(String source) {
        long now = System.currentTimeMillis();
        if (now - lastWaitLogAt < 3000L && !"start".equals(source)) {
            return;
        }
        lastWaitLogAt = now;
        int complete = count(PersonApi.getInstance().getCompleteFaceList());
        int faces = count(PersonApi.getInstance().getAllFaceList());
        DebugLog.i(TAG, "wait face id complete=" + complete
                + " faces=" + faces
                + " sample=" + sampleFaceIds(PersonApi.getInstance().getAllFaceList()));
    }

    private static int count(List<Person> list) {
        return list == null ? 0 : list.size();
    }

    private static String sampleFaceIds(List<Person> list) {
        if (list == null || list.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Person p : list) {
            if (p == null) {
                continue;
            }
            if (n > 0) {
                sb.append(',');
            }
            sb.append(p.getId())
                    .append('@')
                    .append(String.format("%.2f", p.getDistance()))
                    .append("w")
                    .append(p.getFaceWidth());
            if (++n >= 3) {
                break;
            }
        }
        return n == 0 ? "none" : sb.toString();
    }

    private void startFocusFollowForPerson(Person person, RobotConfig.FocusFollowConfig cfg) {
        if (person == null || following || startingFollow) {
            return;
        }
        final int faceId = person.getId();
        if (faceId < 0 || faceId == issuedFaceId) {
            return;
        }
        if (callback != null && callback.isChassisBusy()) {
            DebugLog.w(TAG, "skip startFocusFollow: chassis busy");
            return;
        }
        if (!isFollowableNearFace(person, resolveFollowDistance(cfg), true)) {
            DebugLog.i(TAG, "skip startFocusFollow: face gone or too far id=" + faceId
                    + " d=" + String.format("%.2f", person.getDistance())
                    + " face=" + person.getFaceWidth() + "x" + person.getFaceHeight());
            return;
        }
        long lostTimeoutMs = cfg.lostTimeoutSec > 0 ? cfg.lostTimeoutSec * 1000L : 3000L;
        if (lostTimeoutMs < 2000L) {
            lostTimeoutMs = 2000L;
        }
        if (lostTimeoutMs > 4000L) {
            lostTimeoutMs = 4000L;
        }
        // 传给底盘的最大距离也用近场，避免系统把 2 米外的脸继续跟下去
        float maxDistanceM = resolveFollowDistance(cfg);
        DebugLog.i(TAG, "startFocusFollow faceId=" + faceId
                + " d=" + String.format("%.2f", person.getDistance())
                + " viewAng=" + String.format("%.1f", faceYaw(person))
                + " lostMs=" + lostTimeoutMs
                + " maxM=" + maxDistanceM);
        // 先锁住这个人脸。文档：不要反复 startFocusFollow 跟踪同一个人。
        issuedFaceId = faceId;
        try {
            startingFollow = true;
            int code = RobotApi.getInstance().startFocusFollow(
                    REQ_ID,
                    faceId,
                    lostTimeoutMs,
                    maxDistanceM,
                    buildFollowListener(faceId, cfg));
            startingFollow = false;
            DebugLog.i(TAG, "startFocusFollow return=" + code);
            if (code == Definition.ACTION_RESPONSE_ALREADY_RUN || code >= 0) {
                followAccepted = true;
                followingFaceId = faceId;
                following = true;
                notifyFollowing(true);
                return;
            }
            followAccepted = false;
            following = false;
            if (code == Definition.ACTION_RESPONSE_REQUEST_RES_ERROR) {
                issuedFaceId = -1;
                nextStartAllowedAt = System.currentTimeMillis() + 3000L;
                DebugLog.w(TAG, "startFocusFollow chassis busy code=" + code);
                return;
            }
            DebugLog.w(TAG, "startFocusFollow rejected code=" + code + ", will not retry this face");
        } catch (Throwable t) {
            startingFollow = false;
            DebugLog.e(TAG, "startFocusFollow failed", t);
        }
    }

    private ActionListener buildFollowListener(int targetId, RobotConfig.FocusFollowConfig cfg) {
        return new ActionListener() {
            @Override
            public void onStatusUpdate(int status, String data) throws android.os.RemoteException {
                mainHandler.post(() -> handleFollowStatus(status, targetId, cfg));
            }

            @Override
            public void onError(int errorCode, String errorString) throws android.os.RemoteException {
                DebugLog.w(TAG, "focus follow error " + errorCode + " " + errorString);
                mainHandler.post(() -> handleFollowError(errorCode));
            }

            @Override
            public void onResult(int status, String responseString) throws android.os.RemoteException {
                if (status == Definition.ACTION_RESPONSE_STOP_SUCCESS) {
                    mainHandler.post(() -> releaseFollowLock(500L));
                }
            }
        };
    }

    private void handleFollowError(int errorCode) {
        if (errorCode == Definition.ACTION_RESPONSE_ALREADY_RUN) {
            following = true;
            followAccepted = true;
            DebugLog.i(TAG, "focus follow already running, do not start again");
            return;
        }
        if (errorCode == Definition.ACTION_RESPONSE_REQUEST_RES_ERROR) {
            DebugLog.w(TAG, "focus follow chassis busy, wait");
            releaseFollowLock(3000L);
            return;
        }
        if (errorCode == Definition.ERROR_SET_TRACK_FAILED
                || errorCode == Definition.ERROR_TARGET_NOT_FOUND) {
            DebugLog.w(TAG, "focus follow target lost code=" + errorCode);
            releaseFollowLock(1500L);
            return;
        }
        DebugLog.w(TAG, "focus follow error " + errorCode + ", keep current face locked");
        following = false;
        followAccepted = false;
        startingFollow = false;
    }

    /** 上次智能跟随可能还占着底盘。这台机器不支持该动作，启动时清一次，失败忽略。 */
    private void releaseLeftoverSmartFollow() {
        if (releasedLeftoverSmart) {
            return;
        }
        releasedLeftoverSmart = true;
        try {
            RobotApi.getInstance().stopSmartFocusFollow(SMART_REQ_ID);
        } catch (Throwable ignored) {
        }
    }

    private void handleFollowStatus(int status, int faceId, RobotConfig.FocusFollowConfig cfg) {
        switch (status) {
            case Definition.STATUS_TRACK_TARGET_SUCCEED:
            case Definition.STATUS_GUEST_NEAR:
            case Definition.STATUS_GUEST_APPEAR:
                scheduleWelcomeCheck(faceId, cfg);
                break;
            case Definition.STATUS_GUEST_LOST:
            case Definition.STATUS_GUEST_FARAWAY:
                DebugLog.i(TAG, "stop focus follow: sdk status=" + status);
                stopRunningFollow();
                break;
            default:
                break;
        }
    }

    private void scheduleWelcomeCheck(int personId, RobotConfig.FocusFollowConfig cfg) {
        if (!active || welcomeSignaled) {
            return;
        }
        float maxDist = welcomeDistance(cfg);
        float maxAngle = welcomeAngle(cfg);
        try {
            Person best = findPersonById(PersonApi.getInstance().getAllFaceList(), personId);
            if (best == null) {
                best = findPersonById(PersonApi.getInstance().getCompleteFaceList(), personId);
            }
            if (best == null) {
                best = pickFrontNearForWelcome(cfg);
            }
            if (best == null || !isFrontNear(best, maxDist, maxAngle)) {
                // 计时已经开始时，状态回调里的瞬时漏检不要把迎宾清掉
                if (approachRunnable == null) {
                    cancelApproachCheck();
                }
                return;
            }
            // 人还在面前就不要因为人脸 id 抖动把计时清掉，否则保持时间永远到不了
            if (approachRunnable != null) {
                return;
            }
            if (approachSince == 0L) {
                approachSince = System.currentTimeMillis();
                long holdMs = Math.max(500L, (long) (cfg.approachHoldSec * 1000f));
                final int targetId = best.getId() > 0 ? best.getId() : personId;
                approachRunnable = () -> {
                    if (!active || welcomeSignaled) {
                        return;
                    }
                    Person again = findPersonById(PersonApi.getInstance().getAllFaceList(), targetId);
                    if (again == null) {
                        again = pickFrontNearForWelcome(cfg);
                    }
                    if (again != null && isFrontNear(again, maxDist, maxAngle)) {
                        DebugLog.i(TAG, "welcome front-near d="
                                + String.format("%.2f", again.getDistance())
                                + " faceAng=" + String.format("%.1f", faceYaw(again)));
                        triggerWelcome(String.valueOf(again.getId() > 0 ? again.getId() : targetId));
                    } else {
                        cancelApproachCheck();
                    }
                };
                mainHandler.postDelayed(approachRunnable, holdMs);
            }
        } catch (Throwable t) {
            DebugLog.e(TAG, "scheduleWelcomeCheck error", t);
        }
    }

    private Person findPersonById(List<Person> persons, int personId) {
        if (persons == null) {
            return null;
        }
        for (Person p : persons) {
            if (p != null && p.getId() == personId) {
                return p;
            }
        }
        return null;
    }

    private void triggerWelcome(String personId) {
        if (!active || welcomeSignaled) {
            return;
        }
        welcomeSignaled = true;
        cancelApproachCheck();
        // 迎宾不要 stopFocusFollow。文档：同一个人只启动一次，主动停止后才能再启动。
        if (callback != null) {
            callback.onPersonReadyForWelcome(personId);
        }
    }

    private void releaseFollowLock(long retryDelayMs) {
        followAccepted = false;
        following = false;
        startingFollow = false;
        followingFaceId = -1;
        issuedFaceId = -1;
        faceAnchor = null;
        followMotionAnchor = null;
        absentSince = 0L;
        stillSince = 0L;
        nextStartAllowedAt = System.currentTimeMillis() + Math.max(0L, retryDelayMs);
        notifyFollowing(false);
    }

    private void clearLocalFollow() {
        releaseFollowLock(0L);
    }

    /** 只有系统确实接过这次跟随才停止。没启动就 stop，SDK 内部 ActionInfo 为空会空指针。 */
    private void stopSdkFollowIfAccepted() {
        if (!followAccepted) {
            return;
        }
        followAccepted = false;
        try {
            RobotApi.getInstance().stopFocusFollow(REQ_ID);
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? "" : t.getMessage();
            if (!msg.contains("ActionInfo")) {
                DebugLog.w(TAG, "stop focus follow failed:" + msg);
            }
        }
    }

    private void cancelApproachCheck() {
        approachSince = 0L;
        pendingWelcomePersonId = -1;
        if (approachRunnable != null) {
            mainHandler.removeCallbacks(approachRunnable);
            approachRunnable = null;
        }
    }

    private void notifyFollowing(boolean value) {
        if (callback != null) {
            mainHandler.post(() -> callback.onFollowingChanged(value));
        }
    }

    private static boolean movedSignificantly(FaceSample prev, FaceSample now) {
        return movedSignificantly(prev, now, false);
    }

    private static boolean movedSignificantly(FaceSample prev, FaceSample now, boolean ignoreAngles) {
        if (prev == null || now == null || prev.id != now.id) {
            return false;
        }
        if (Math.abs(now.distance - prev.distance) >= MOVE_DISTANCE_M && now.distance > 0 && prev.distance > 0) {
            return true;
        }
        if (!ignoreAngles && Math.abs(now.faceAngleX - prev.faceAngleX) >= MOVE_ANGLE_X) {
            return true;
        }
        if (!ignoreAngles && Math.abs(now.faceAngleY - prev.faceAngleY) >= MOVE_ANGLE_Y) {
            return true;
        }
        if (faceShift(prev, now) >= MOVE_BOX_PX) {
            return true;
        }
        return bodyShift(prev, now) >= MOVE_BOX_PX;
    }

    private static int faceShift(FaceSample prev, FaceSample now) {
        if (prev.faceWidth <= 0 || now.faceWidth <= 0 || prev.faceHeight <= 0 || now.faceHeight <= 0) {
            return 0;
        }
        int px = prev.faceX + prev.faceWidth / 2;
        int py = prev.faceY + prev.faceHeight / 2;
        int nx = now.faceX + now.faceWidth / 2;
        int ny = now.faceY + now.faceHeight / 2;
        return Math.abs(nx - px) + Math.abs(ny - py);
    }

    private static int bodyShift(FaceSample prev, FaceSample now) {
        if (prev.bodyX == 0 && prev.bodyY == 0) {
            return 0;
        }
        if (now.bodyX == 0 && now.bodyY == 0) {
            return 0;
        }
        return Math.abs(now.bodyX - prev.bodyX) + Math.abs(now.bodyY - prev.bodyY);
    }

    private static final class FaceSample {
        final int id;
        final double distance;
        final double faceAngleX;
        final double faceAngleY;
        final int headSpeed;
        final long latency;
        final int faceWidth;
        final int faceHeight;
        final int faceX;
        final int faceY;
        final int bodyX;
        final int bodyY;

        private FaceSample(int id, double distance, double faceAngleX, double faceAngleY,
                           int headSpeed, long latency, int faceWidth, int faceHeight,
                           int faceX, int faceY, int bodyX, int bodyY) {
            this.id = id;
            this.distance = distance;
            this.faceAngleX = faceAngleX;
            this.faceAngleY = faceAngleY;
            this.headSpeed = headSpeed;
            this.latency = latency;
            this.faceWidth = faceWidth;
            this.faceHeight = faceHeight;
            this.faceX = faceX;
            this.faceY = faceY;
            this.bodyX = bodyX;
            this.bodyY = bodyY;
        }

        static FaceSample capture(Person person) {
            if (person == null) {
                return null;
            }
            try {
                return new FaceSample(
                        person.getId(),
                        person.getDistance(),
                        person.getFaceAngleX(),
                        person.getFaceAngleY(),
                        person.getHeadSpeed(),
                        person.getLatency(),
                        person.getFaceWidth(),
                        person.getFaceHeight(),
                        person.getFaceX(),
                        person.getFaceY(),
                        person.getBodyX(),
                        person.getBodyY());
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
