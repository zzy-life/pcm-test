package com.hr.digitalhuman.robot;

import android.os.Handler;
import android.os.Looper;

import com.ainirobot.coreservice.client.listener.Person;
import com.ainirobot.coreservice.client.person.PersonApi;
import com.ainirobot.coreservice.client.person.PersonListener;
import com.ainirobot.coreservice.client.person.PersonUtils;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.RobotConfig;

import java.util.List;

/**
 * 近场人员检测：迎宾、离场回待机，以及「面前无人则忽略语音」。
 * 正前方近场有人（或丢失后短宽限内）才放行 ASR；无人在场一律丢弃，避免空场噪音抢麦。
 */
public class PersonPresenceTracker {

    private static final String TAG = "PersonPresence";
    private static final long SCAN_INTERVAL_MS = 350L;
    private static final float DEFAULT_VOICE_DISTANCE_M = 2.0f;
    private static final float DEFAULT_VOICE_ANGLE_X = 60f;
    private static final float MOUTH_SPEAK_SCORE = 0.12f;
    /** 丢失后 ASR/在场宽限期 */
    private static final long VOICE_GRACE_MS = 3000L;
    private static final long ABSENT_HOLD_MS = 1500L;
    /** 人员离开后自动回待机的默认时长 */
    private static final long DEFAULT_LEAVE_STANDBY_MS = 5000L;
    /**
     * 回待机用的人脸距离。跟随仍用 1.8~2.2 米；本机把面前的人经常测成 3 米出头，
     * 按跟随距离判断会把人还在面前当成已经离开。
     */
    private static final float STANDBY_FACE_M = 4.0f;
    /** 刚看到正前方的人之后，短时间丢帧也不把语音近场清掉。 */
    private static final long FRONT_HOLD_MS = 12000L;
    /** 这个距离内的人一律视为还在面前，转头造成的偏角不能当成离开。 */
    private static final float NEAR_KEEP_M = 1.5f;
    /** 1.5 米内刚看到过人：丢帧后短时仍按在场，过长会让人离开后回不了待机。 */
    private static final long NEAR_KEEP_MS = 5000L;
    /** 测距常把 1.5 米内的人报大一点；正前方不超过该距离仍视为在面前。 */
    private static final float STANDBY_DISTANCE_FUDGE_M = 2.0f;
    /** 测距无效时，用人脸框大小判断是否还在近场；远处小脸不能挡住回待机。 */
    private static final int NEAR_FACE_MIN_PX = 48;

    public interface Listener {
        void onPresenceChanged(boolean present);

        /** 连续检测不到人达到阈值（默认 5 秒） */
        void onPersonLeftTimeout();

        /** 1.5 米内有人：空闲计时不应把界面带回待机。 */
        void onPersonNearby(boolean nearby);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private PersonListener personListener;
    private Runnable scanRunnable;
    private boolean active;
    private boolean present;
    private long lastPresentAt;
    private long lostSince;
    /** 首次连续丢人时间戳；有人时清零 */
    private long absentSince;
    private boolean leaveTimeoutFired;
    private double lastNearestDistanceM = -1;
    private double lastFaceAngleX = 999;
    private boolean mouthSignalEverSeen;
    private boolean lastLikelySpeaking;
    /** 视野内存在非正前方人员时为 true，用于抑制旁人抢麦 */
    private boolean lastSideInterference;
    private long lastFrontSeenAt;
    private long lastCloseSeenAt;
    private long lastNearbyNotifyAt;

    public synchronized void start(Listener listener) {
        this.listener = listener;
        if (active) {
            refreshNow("restart");
            return;
        }
        active = true;
        present = false;
        lastPresentAt = 0;
        lostSince = 0;
        absentSince = 0;
        leaveTimeoutFired = false;
        lastNearestDistanceM = -1;
        lastFaceAngleX = 999;
        lastLikelySpeaking = false;
        lastSideInterference = false;
        mouthSignalEverSeen = false;
        lastFrontSeenAt = 0;
        lastCloseSeenAt = 0;
        lastNearbyNotifyAt = 0;
        registerPersonListener();
        startPeriodicScan();
        refreshNow("start");
    }

    public synchronized void stop() {
        active = false;
        stopPeriodicScan();
        unregisterPersonListener();
        if (present) {
            present = false;
            notifyPresence(false);
        }
    }

    public synchronized boolean isPersonPresent() {
        return present;
    }

    /** 面前近场有人（含短宽限）。 */
    public synchronized boolean isVoiceEligible() {
        if (present) {
            return true;
        }
        return lastPresentAt > 0 && System.currentTimeMillis() - lastPresentAt < VOICE_GRACE_MS;
    }

    /**
     * 语音门禁：正前方近场（含短宽限）才处理。
     * 不用 getAllPersons(1米)：本机面前的人常被测成 1.8~2.2 米，1 米门禁会把麦关掉。
     * 也不用 PersonUtils.isFace：本机不报 liveNess，isFace 会把真人判没。
     */
    public synchronized boolean shouldAcceptAsr() {
        if (isVoiceEligible()) {
            return true;
        }
        boolean near = detectNearFieldPerson();
        if (!near) {
            DebugLog.i(TAG, "ASR rejected near=false maxM=" + resolveVoiceDistanceM());
        }
        return near;
    }

    /**
     * 声源角度软门禁：仅拦截明显侧后方（&gt;75°）；未知/正前一律放行。
     */
    public synchronized boolean shouldAcceptSoundAngle(double soundAngleDeg) {
        // 宽于视觉角，避免声源定位抖动误杀
        float maxAngle = Math.max(75f, resolveVoiceAngleX() * 2f);
        if (Math.abs(soundAngleDeg) > maxAngle) {
            DebugLog.i(TAG, "soundAngle rejected abs=" + Math.abs(soundAngleDeg)
                    + " > max=" + maxAngle);
            return false;
        }
        return true;
    }

    public synchronized double getLastNearestDistanceM() {
        return lastNearestDistanceM;
    }

    public synchronized double getLastFaceAngleX() {
        return lastFaceAngleX;
    }

    /** 刷新近场检测；返回值带滞回，避免单帧丢人脸就关麦。 */
    public synchronized boolean refreshNow(String source) {
        boolean liveClose = scanSomeoneWithin(NEAR_KEEP_M);
        if (liveClose) {
            lastCloseSeenAt = System.currentTimeMillis();
        }
        boolean close = liveClose || recentlyClose();
        boolean voiceNear = detectNearFieldPerson();
        // 离场回待机只看近场/正前方 2 米，不用语音距离，否则 2 米外的人会拖住离场计时
        boolean occupied = close || scanFrontWithin(STANDBY_DISTANCE_FUDGE_M);
        applyPresence(voiceNear, occupied, source);
        if (liveClose) {
            notifyNearby();
        }
        return isVoiceEligible();
    }

    /**
     * 1.5 米内是否有人。不看水平偏角：机器人转头时角度会变大，人并没有走开。
     * 距离读数无效时，仅正前方人脸算在面前。
     */
    public synchronized boolean someoneWithin(float meters) {
        float limit = meters > 0 ? meters : NEAR_KEEP_M;
        if (scanSomeoneWithin(limit)) {
            lastCloseSeenAt = System.currentTimeMillis();
            return true;
        }
        return lastCloseSeenAt > 0
                && System.currentTimeMillis() - lastCloseSeenAt < NEAR_KEEP_MS;
    }

    /**
     * 是否禁止回待机。条件是 1.5 米内有人脸或人形。
     * 测距偶尔偏大，正前方不超过 2 米也算还在面前；大厅更远处的人不挡回待机。
     */
    public synchronized boolean blocksStandby() {
        try {
            refreshNow("standby_check");
        } catch (Throwable t) {
            DebugLog.w(TAG, "standby refresh failed: " + t.getMessage());
        }
        if (hasLivePersonBlockingStandby()) {
            return true;
        }
        return recentlyClose();
    }

    /** 当前帧是否还有人挡回待机（不含丢帧滞回）。 */
    public synchronized boolean hasLivePersonBlockingStandby() {
        if (scanSomeoneWithin(NEAR_KEEP_M)) {
            lastCloseSeenAt = System.currentTimeMillis();
            return true;
        }
        if (scanFrontWithin(STANDBY_DISTANCE_FUDGE_M)) {
            return true;
        }
        return false;
    }

    private boolean recentlyClose() {
        return lastCloseSeenAt > 0
                && System.currentTimeMillis() - lastCloseSeenAt < NEAR_KEEP_MS;
    }

    /** 正前方指定距离内是否有人脸或人形。 */
    private boolean scanFrontWithin(float limit) {
        float maxDist = limit > 0 ? limit : STANDBY_DISTANCE_FUDGE_M;
        float maxAngle = 45f;
        try {
            if (hasFrontFace(PersonApi.getInstance().getAllFaceList(), maxDist, maxAngle)
                    || hasFrontFace(PersonApi.getInstance().getAllPersons(), maxDist, maxAngle)) {
                return true;
            }
            return pickBestFrontBody(PersonApi.getInstance().getAllBodyList(), maxDist, maxAngle) != null;
        } catch (Throwable t) {
            DebugLog.w(TAG, "scanFrontWithin failed: " + t.getMessage());
            return false;
        }
    }

    /** 取消尚未执行的离场回待机，避免人还在 1.5 米内时计时器再次触发。 */
    public synchronized void acknowledgeStillHere() {
        absentSince = 0;
        lostSince = 0;
        leaveTimeoutFired = false;
    }

    /** 面前仍有人（迎宾/检测距离内），用于阻止误回待机。比语音近场更宽。 */
    public synchronized boolean isFrontOccupied() {
        if (detectFrontOccupied() || recentlySawFront()) {
            return true;
        }
        return present && lastPresentAt > 0
                && System.currentTimeMillis() - lastPresentAt < VOICE_GRACE_MS;
    }

    private void registerPersonListener() {
        if (personListener != null) {
            return;
        }
        personListener = new PersonListener() {
            @Override
            public void personChanged() {
                super.personChanged();
                mainHandler.post(() -> refreshNow("personChanged"));
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
                refreshNow("periodic");
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

    /**
     * 近场判定：正前方近场人脸优先；身体仅作弱证据且同样限制水平角。
     */
    private boolean detectNearFieldPerson() {
        float maxDist = resolveVoiceDistanceM();
        float maxAngle = resolveVoiceAngleX();
        boolean requireFace = resolveRequireFace();
        lastNearestDistanceM = -1;
        lastFaceAngleX = 999;
        lastLikelySpeaking = false;
        lastSideInterference = false;
        try {
            // 近场选人用距离过滤；旁人干扰判定用全量视野，避免远侧人员被滤掉后误放行/误杀
            List<Person> facesAll = PersonApi.getInstance().getAllFaceList();
            if (facesAll == null || facesAll.isEmpty()) {
                facesAll = PersonApi.getInstance().getCompleteFaceList();
            }
            List<Person> facesNear = PersonApi.getInstance().getAllFaceList(maxDist);
            if (facesNear == null || facesNear.isEmpty()) {
                facesNear = facesAll;
            }
            Person face = PersonUtils.getBestFace(facesNear, maxDist, maxAngle);
            if (face == null) {
                face = pickBestFrontFace(facesNear, maxDist, maxAngle);
            }
            List<Person> bodiesAll = PersonApi.getInstance().getAllBodyList();
            List<Person> bodiesNear = PersonApi.getInstance().getAllBodyList(maxDist);
            if (bodiesNear == null || bodiesNear.isEmpty()) {
                bodiesNear = bodiesAll;
            }
            lastSideInterference = hasSideInterference(facesAll, bodiesAll, maxDist, maxAngle, face);
            if (face != null) {
                lastNearestDistanceM = face.getDistance();
                lastFaceAngleX = preferViewAngle(face);
                lastLikelySpeaking = isLikelySpeaking(face);
                return true;
            }
            if (requireFace) {
                return false;
            }

            Person body = pickBestFrontBody(bodiesNear, maxDist, maxAngle);
            if (body != null) {
                lastNearestDistanceM = body.getDistance();
                lastFaceAngleX = bodyAngle(body);
                return true;
            }
            return false;
        } catch (Throwable t) {
            DebugLog.w(TAG, "detectNearFieldPerson failed: " + t.getMessage());
            // 视觉异常视为面前无人，不放行 ASR（避免空场误响应）
            lastSideInterference = false;
            return false;
        }
    }

    private Person pickBestFrontFace(List<Person> list, float maxDist, float maxAngle) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        Person best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Person p : list) {
            if (!isVisibleFace(p)) {
                continue;
            }
            double mouth = safeMouthScore(p);
            try {
                if (mouth > 0.01 || p.getMouthState() > 0) {
                    mouthSignalEverSeen = true;
                }
            } catch (Throwable ignored) {
            }
            double d = p.getDistance();
            double ax = Math.min(Math.abs(p.getFaceAngleX()), Math.abs(safeAngleInView(p)));
            if (d <= 0 || d > maxDist || ax > maxAngle) {
                continue;
            }
            // 越近、越居中、嘴部越活跃分越高
            double score = (maxDist - d) * 2.0
                    + (maxAngle - ax) / Math.max(1.0, maxAngle)
                    + mouth * 8.0
                    + (p.getMouthState() > 0 ? 3.0 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private boolean hasSideInterference(List<Person> faces, List<Person> bodies,
                                        float maxDist, float maxAngle, Person frontFace) {
        int others = 0;
        if (faces != null) {
            for (Person p : faces) {
                if (!isVisibleFace(p)) {
                    continue;
                }
                if (frontFace != null && samePerson(frontFace, p)) {
                    continue;
                }
                double d = p.getDistance();
                double ax = Math.min(Math.abs(p.getFaceAngleX()), Math.abs(safeAngleInView(p)));
                // 非正前方近场的其他人脸视为干扰
                if (d > 0 && (d > maxDist || ax > maxAngle)) {
                    others++;
                } else if (d > 0 && frontFace != null) {
                    // 另一张也在正前方的脸（多人）→ 需要嘴部辅助判别
                    others++;
                }
            }
        }
        if (bodies != null) {
            for (Person p : bodies) {
                if (p == null || !PersonUtils.isBody(p)) {
                    continue;
                }
                if (frontFace != null && samePerson(frontFace, p)) {
                    continue;
                }
                double d = p.getDistance();
                double ax = Math.abs(bodyAngle(p));
                if (d > 0 && (ax > maxAngle || d > maxDist * 1.2f)) {
                    others++;
                }
            }
        }
        return others > 0;
    }

    private static boolean samePerson(Person a, Person b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            if (a.getId() >= 0 && a.getId() == b.getId()) {
                return true;
            }
            if (a.getAssociateId() > 0 && a.getAssociateId() == b.getAssociateId()) {
                return true;
            }
            if (a.getId() > 0 && a.getId() == b.getAssociateId()) {
                return true;
            }
            if (b.getId() > 0 && b.getId() == a.getAssociateId()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return a == b;
    }

    private Person pickBestFrontBody(List<Person> list, float maxDist, float maxAngle) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        Person best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Person p : list) {
            if (p == null) {
                continue;
            }
            double d = p.getDistance();
            double ax = Math.abs(bodyAngle(p));
            if (d <= 0 || d > maxDist || ax > maxAngle) {
                continue;
            }
            double score = (maxDist - d) + (maxAngle - ax) / Math.max(1.0, maxAngle);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private static double preferViewAngle(Person p) {
        double view = safeAngleInView(p);
        if (Math.abs(view) < 900) {
            return view;
        }
        return p.getFaceAngleX();
    }

    private static double safeAngleInView(Person p) {
        try {
            return p.getAngleInView();
        } catch (Throwable t) {
            return 999;
        }
    }

    private static double bodyAngle(Person p) {
        try {
            // angleInView / angle：水平方位，0 大致为正前
            double a = p.getAngleInView();
            if (Math.abs(a) > 0.01) {
                return a;
            }
            return p.getAngle();
        } catch (Throwable t) {
            return 999;
        }
    }

    private static double safeMouthScore(Person p) {
        try {
            return p.getMouthMoveScore();
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean isLikelySpeaking(Person p) {
        if (p == null) {
            return false;
        }
        try {
            if (p.getMouthState() > 0) {
                return true;
            }
            return safeMouthScore(p) >= MOUTH_SPEAK_SCORE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 语音距离不低于在场检测距离。后台若仍下发 1 米，面前 2 米左右的人会被当成超距关麦。
     */
    private float resolveVoiceDistanceM() {
        float configured = DEFAULT_VOICE_DISTANCE_M;
        try {
            RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
            if (config != null && config.focusFollow != null
                    && config.focusFollow.voiceListenDistanceM > 0) {
                configured = config.focusFollow.voiceListenDistanceM;
            }
        } catch (Throwable ignored) {
        }
        return Math.max(configured, resolveOccupyDistanceM());
    }

    /** 本机不报 liveNess，PersonUtils.isFace 会拒绝真人。id 大于等于 0 且有人脸框即可。 */
    private static boolean isVisibleFace(Person p) {
        if (p == null || p.getId() < 0) {
            return false;
        }
        try {
            return p.getFaceWidth() > 0 || p.getFaceHeight() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 人脸框足够大才像近场；测距无效时用来排除大厅远处的人。 */
    private static boolean faceLooksNear(Person p) {
        if (p == null) {
            return false;
        }
        try {
            return Math.max(p.getFaceWidth(), p.getFaceHeight()) >= NEAR_FACE_MIN_PX;
        } catch (Throwable t) {
            return false;
        }
    }

    private float resolveVoiceAngleX() {
        try {
            RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
            if (config != null && config.focusFollow != null
                    && config.focusFollow.voiceMaxFaceAngleX > 0) {
                return config.focusFollow.voiceMaxFaceAngleX;
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_VOICE_ANGLE_X;
    }

    private boolean resolveRequireFace() {
        try {
            RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
            if (config != null && config.focusFollow != null) {
                return config.focusFollow.voiceRequireFace;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private boolean detectFrontOccupied() {
        float maxDist = Math.max(resolveOccupyDistanceM(), STANDBY_FACE_M);
        float maxAngle = Math.max(75f, resolveVoiceAngleX());
        try {
            if (hasFrontFace(PersonApi.getInstance().getAllFaceList(), maxDist, maxAngle)
                    || hasFrontFace(PersonApi.getInstance().getAllPersons(), maxDist, maxAngle)) {
                lastFrontSeenAt = System.currentTimeMillis();
                return true;
            }
            List<Person> bodies = PersonApi.getInstance().getAllBodyList();
            if (pickBestFrontBody(bodies, maxDist, maxAngle) != null) {
                lastFrontSeenAt = System.currentTimeMillis();
                return true;
            }
            return false;
        } catch (Throwable t) {
            DebugLog.w(TAG, "front occupy detect failed: " + t.getMessage());
            return present || recentlySawFront();
        }
    }

    /** 正前方可见人脸。不用距离过滤接口，也不用 isFace（本机不报活体）。 */
    private boolean hasFrontFace(List<Person> list, float maxDist, float maxAngle) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (Person p : list) {
            if (!isVisibleFace(p)) {
                continue;
            }
            double d = 0;
            try {
                d = p.getDistance();
            } catch (Throwable ignored) {
            }
            if (d > 0) {
                if (d > maxDist) {
                    continue;
                }
                // 1.5 米内不看偏角，避免焦点跟随转头时被误判为离开。
                if (d > NEAR_KEEP_M) {
                    double ax = Math.min(Math.abs(p.getFaceAngleX()), Math.abs(safeAngleInView(p)));
                    if (ax > maxAngle) {
                        continue;
                    }
                }
                return true;
            }
            // 测距无效：远处小脸不能当成还在面前，否则大厅有人就回不了待机。
            if (!faceLooksNear(p)) {
                continue;
            }
            double ax = Math.min(Math.abs(p.getFaceAngleX()), Math.abs(safeAngleInView(p)));
            if (ax > maxAngle) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean recentlySawFront() {
        return lastFrontSeenAt > 0
                && System.currentTimeMillis() - lastFrontSeenAt < FRONT_HOLD_MS;
    }

    private boolean scanSomeoneWithin(float limit) {
        try {
            if (listHasWithin(PersonApi.getInstance().getAllFaceList(), limit, true)) {
                return true;
            }
            if (listHasWithin(PersonApi.getInstance().getAllPersons(), limit, true)) {
                return true;
            }
            return listHasWithin(PersonApi.getInstance().getAllBodyList(), limit, false);
        } catch (Throwable t) {
            DebugLog.w(TAG, "someoneWithin failed: " + t.getMessage());
            return false;
        }
    }

    /** 有效距离在阈值内算在面前；测距无效时仅近场大小的正前方人脸算在面前。身体必须有正的距离。 */
    private boolean listHasWithin(List<Person> list, float limit, boolean face) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (Person p : list) {
            if (p == null) {
                continue;
            }
            if (face && !isVisibleFace(p)) {
                continue;
            }
            double d = 0;
            try {
                d = p.getDistance();
            } catch (Throwable ignored) {
            }
            if (d > 0 && d <= limit) {
                return true;
            }
            if (face && d <= 0 && faceLooksNear(p)) {
                double ax = 999;
                try {
                    ax = Math.abs(p.getFaceAngleX());
                } catch (Throwable ignored) {
                }
                // 测距无效时只把近场大小的正前方人脸当成在面前
                if (ax <= 45f) {
                    return true;
                }
            }
        }
        return false;
    }

    private void notifyNearby() {
        long now = System.currentTimeMillis();
        if (now - lastNearbyNotifyAt < 1000L) {
            return;
        }
        lastNearbyNotifyAt = now;
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPersonNearby(true);
            }
        });
    }

    /** 在场判定用检测距离，不用更短的语音距离，避免人站在面前却被当成离开。 */
    private float resolveOccupyDistanceM() {
        float d = 1.8f;
        try {
            RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
            if (config != null && config.focusFollow != null) {
                if (config.focusFollow.detectDistanceM > 0) {
                    d = config.focusFollow.detectDistanceM;
                } else if (config.focusFollow.welcomeDistanceM > 0) {
                    d = config.focusFollow.welcomeDistanceM;
                }
            }
        } catch (Throwable ignored) {
        }
        if (d < 1.5f) {
            d = 1.5f;
        }
        if (d > 2.2f) {
            d = 2.2f;
        }
        return d;
    }

    private void applyPresence(boolean voiceNear, boolean occupied, String source) {
        long t = System.currentTimeMillis();
        if (occupied) {
            lastPresentAt = t;
            absentSince = 0;
            leaveTimeoutFired = false;
        }
        if (voiceNear) {
            lastPresentAt = t;
            lostSince = 0;
            absentSince = 0;
            leaveTimeoutFired = false;
            if (!present) {
                present = true;
                DebugLog.i(TAG, "near-field front (" + source + ") d="
                        + String.format("%.2f", lastNearestDistanceM)
                        + " ang=" + String.format("%.1f", lastFaceAngleX)
                        + " speak=" + lastLikelySpeaking);
                notifyPresence(true);
            }
            return;
        }

        // 连续无人计时：面前仍有人则不计离开
        if (!occupied) {
            if (absentSince == 0) {
                absentSince = t;
            }
            long leaveMs = resolveLeaveStandbyMs();
            // 仅在本轮曾检测到人之后，连续无人满阈值才回待机（避免开机无人误触发）
            if (!leaveTimeoutFired && lastPresentAt > 0 && t - absentSince >= leaveMs) {
                leaveTimeoutFired = true;
                DebugLog.i(TAG, "person left timeout " + leaveMs + "ms (" + source + ")");
                notifyPersonLeftTimeout();
            }
        }

        if (!present) {
            return;
        }
        if (lostSince == 0) {
            lostSince = t;
            return;
        }
        if (t - lostSince < ABSENT_HOLD_MS) {
            return;
        }
        present = false;
        lostSince = 0;
        lastLikelySpeaking = false;
        DebugLog.i(TAG, "near-field lost after hold (" + source + ")");
        notifyPresence(false);
    }

    private long resolveLeaveStandbyMs() {
        try {
            RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
            if (config != null && config.session != null
                    && config.session.personLeaveStandbySec > 0) {
                return config.session.personLeaveStandbySec * 1000L;
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_LEAVE_STANDBY_MS;
    }

    private void notifyPresence(boolean value) {
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPresenceChanged(value);
            }
        });
    }

    private void notifyPersonLeftTimeout() {
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPersonLeftTimeout();
            }
        });
    }
}
