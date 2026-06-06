package com.ruoyi.web.controller.course;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.framework.config.ServerConfig;
import org.springframework.web.multipart.MultipartFile;

/**
 * 网课三端联调用接口。
 *
 * 轻量 JSON 持久化，便于直接跑通 uni-app、Vue 管理端、RuoYi 后端。
 */
@RestController
@RequestMapping("/course")
public class CourseApiController
{
    private static final String SAMPLE_VIDEO_URL = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4";
    private static final String DATA_FILE_NAME = "course-data.json";
    private static final Set<String> ALLOWED_TRIAL_SUBJECTS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("yuwen", "shuxue", "math", "gaokao-math", "yingyu", "wuli", "huaxue"))
    );

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Object storeLock = new Object();
    private static final List<Map<String, Object>> users = new ArrayList<>();
    private static final List<Map<String, Object>> courses = new ArrayList<>();
    private static final List<Map<String, Object>> enrollments = new ArrayList<>();
    private static final List<Map<String, Object>> docs = new ArrayList<>();
    private static final List<Map<String, Object>> questions = new ArrayList<>();
    private static final List<Map<String, Object>> reinforcePoints = new ArrayList<>();
    private static final List<Map<String, Object>> studyPlans = new ArrayList<>();
    private static final List<Map<String, Object>> orders = new ArrayList<>();
    private static final List<Map<String, Object>> authRequests = new ArrayList<>();
    private static final List<Map<String, Object>> activationCodes = new ArrayList<>();
    private static final List<Map<String, Object>> favorites = new ArrayList<>();
    private static final List<Map<String, Object>> feedbacks = new ArrayList<>();
    private static final List<Map<String, Object>> studentBindings = new ArrayList<>();
    private static final List<Map<String, Object>> attempts = new ArrayList<>();
    private static final List<Map<String, Object>> wrongQuestions = new ArrayList<>();
    private static final List<Map<String, Object>> lessonRatings = new ArrayList<>();
    private static final List<Map<String, Object>> aiChats = new ArrayList<>();
    private static final List<Map<String, Object>> operationLogs = new ArrayList<>();
    private static final Map<String, Object> frontendSettings = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> lessonProgress = new ConcurrentHashMap<>();
    private static final Map<String, String> verificationCodes = new ConcurrentHashMap<>();

    @Value("${ruoyi.profile:}")
    private String profilePath;

    @Autowired
    private ServerConfig serverConfig;

    static
    {
        initUsers();
        initCourses();
        initDocs();
        initQuestions();
        initActivationCodes();
        initStudyData();
        initFrontendSettings();
    }

    @PostConstruct
    public void loadPersistedData()
    {
        File file = dataFile();
        if (!file.exists())
        {
            return;
        }
        synchronized (storeLock)
        {
            try
            {
                Map<String, Object> data = objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
                restoreList(data, "users", users);
                restoreList(data, "courses", courses);
                restoreList(data, "enrollments", enrollments);
                restoreList(data, "docs", docs);
                restoreList(data, "questions", questions);
                restoreList(data, "reinforcePoints", reinforcePoints);
                restoreList(data, "studyPlans", studyPlans);
                restoreList(data, "orders", orders);
                restoreList(data, "authRequests", authRequests);
                restoreList(data, "activationCodes", activationCodes);
                restoreList(data, "favorites", favorites);
                restoreList(data, "feedbacks", feedbacks);
                restoreList(data, "studentBindings", studentBindings);
                restoreList(data, "attempts", attempts);
                restoreList(data, "wrongQuestions", wrongQuestions);
                restoreList(data, "lessonRatings", lessonRatings);
                restoreList(data, "aiChats", aiChats);
                restoreList(data, "operationLogs", operationLogs);
                restoreMap(data, "frontendSettings", frontendSettings);
                restoreProgress(data);
                boolean changed = normalizeWrongQuestions();
                if (ensureFrontendSettings())
                {
                    changed = true;
                }
                if (ensureBindTestStudent())
                {
                    changed = true;
                }
                if (removeUnsupportedTrialCourses())
                {
                    changed = true;
                }
                if (ensureCoreTrialCourses())
                {
                    changed = true;
                }
                if (ensureGaokaoSupplementCourses())
                {
                    changed = true;
                }
                if (ensureGaokaoReinforcePoints())
                {
                    changed = true;
                }
                if (ensureCourseDocDefaults())
                {
                    changed = true;
                }
                if (changed)
                {
                    persistData();
                }
            }
            catch (IOException e)
            {
                throw new IllegalStateException("读取课程数据失败", e);
            }
        }
    }

    @PostMapping("/app/login")
    public AjaxResult login(@RequestBody Map<String, Object> body)
    {
        String phone = str(body.get("phone"));
        String password = str(body.get("password"));
        for (Map<String, Object> user : users)
        {
            if (phone.equals(user.get("phone")) && password.equals(user.get("password")))
            {
                Map<String, Object> data = map("token", "local-" + user.get("id"), "user", publicUser(user));
                return AjaxResult.success(data);
            }
        }
        return AjaxResult.error("账号或密码错误");
    }

    @PostMapping("/app/register")
    public AjaxResult register(@RequestBody Map<String, Object> body)
    {
        String phone = str(body.get("phone")).trim();
        String password = str(body.get("password"));
        String name = str(body.get("name")).trim();
        String smsCode = str(body.get("smsCode")).trim();
        if (!phone.matches("^1\\d{10}$"))
        {
            return AjaxResult.error("请输入正确的手机号");
        }
        if (smsCode.length() > 0 && !smsCode.equals(verificationCodes.get(phone)))
        {
            return AjaxResult.error("验证码错误或已过期");
        }
        if (password.length() < 6 || password.length() > 32)
        {
            return AjaxResult.error("密码长度需为 6 到 32 位");
        }
        synchronized (users)
        {
            for (Map<String, Object> user : users)
            {
                if (phone.equals(user.get("phone")))
                {
                    return AjaxResult.error("手机号已注册，请直接登录");
                }
            }
            if (name.length() == 0)
            {
                name = "同学" + phone.substring(7);
            }
            Map<String, Object> user = map(
                "phone", phone,
                "password", password,
                "name", name,
                "id", generateUserId(),
                "tenantId", 52,
                "role", "student",
                "status", "active",
                "createdAt", now()
            );
            users.add(user);
            persistData();
            Map<String, Object> data = map("token", "local-" + user.get("id"), "user", publicUser(user));
            return AjaxResult.success(data);
        }
    }

    @PostMapping("/app/sms-code")
    public AjaxResult sendSmsCode(@RequestBody Map<String, Object> body)
    {
        String phone = str(body.get("phone")).trim();
        if (!phone.matches("^1\\d{10}$"))
        {
            return AjaxResult.error("请输入正确的手机号");
        }
        verificationCodes.put(phone, "123456");
        return AjaxResult.success(map("phone", phone, "code", "123456", "message", "演示环境验证码为 123456"));
    }

    @PostMapping("/app/password/reset")
    public AjaxResult resetPassword(@RequestBody Map<String, Object> body)
    {
        String phone = str(body.get("phone")).trim();
        String smsCode = str(body.get("smsCode")).trim();
        String password = str(body.get("password"));
        if (!smsCode.equals(verificationCodes.get(phone)))
        {
            return AjaxResult.error("验证码错误或已过期");
        }
        if (password.length() < 6 || password.length() > 32)
        {
            return AjaxResult.error("密码长度需为 6 到 32 位");
        }
        for (Map<String, Object> user : users)
        {
            if (phone.equals(user.get("phone")))
            {
                user.put("password", password);
                persistData();
                return AjaxResult.success(map("phone", phone));
            }
        }
        return AjaxResult.error("手机号未注册");
    }

    @GetMapping("/app/profile")
    public AjaxResult appProfile(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        return AjaxResult.success(user == null ? new LinkedHashMap<String, Object>() : publicUser(user));
    }

    @PostMapping("/app/profile")
    public AjaxResult updateProfile(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        if (user == null)
        {
            return AjaxResult.error("请先登录");
        }
        String phone = str(body.get("phone")).trim();
        if (phone.length() > 0 && !phone.equals(str(user.get("phone"))))
        {
            if (!phone.matches("^1\\d{10}$"))
            {
                return AjaxResult.error("请输入正确的手机号");
            }
            for (Map<String, Object> item : users)
            {
                if (!str(user.get("id")).equals(str(item.get("id"))) && phone.equals(str(item.get("phone"))))
                {
                    return AjaxResult.error("手机号已被其他账号使用");
                }
            }
            user.put("phone", phone);
        }
        putIfPresent(user, body, "avatar");
        putIfPresent(user, body, "name");
        putIfPresent(user, body, "nickname");
        putIfPresent(user, body, "realName");
        putIfPresent(user, body, "address");
        putIfPresent(user, body, "wechat");
        putIfPresent(user, body, "wechatBound");
        putIfPresent(user, body, "faceUploaded");
        putIfPresent(user, body, "answerAudioEnabled");
        String password = str(body.get("password"));
        if (password.length() > 0)
        {
            if (password.length() < 6 || password.length() > 32)
            {
                return AjaxResult.error("密码长度需为 6 到 32 位");
            }
            user.put("password", password);
        }
        user.put("updatedAt", now());
        persistData();
        return AjaxResult.success(publicUser(user));
    }

    @PostMapping("/app/feedback")
    public AjaxResult submitFeedback(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        String content = str(body.get("content")).trim();
        if (content.length() == 0)
        {
            return AjaxResult.error("请输入反馈内容");
        }
        Map<String, Object> feedback = map(
            "id", "feedback-" + System.currentTimeMillis(),
            "userId", user == null ? null : user.get("id"),
            "userName", user == null ? "" : user.get("name"),
            "phone", str(body.get("phone")).trim(),
            "wechat", str(body.get("wechat")).trim(),
            "content", content,
            "images", stringList(body.get("images")),
            "status", "new",
            "createdAt", now()
        );
        feedbacks.add(feedback);
        persistData();
        return AjaxResult.success(feedback);
    }

    @GetMapping("/app/settings")
    public AjaxResult appFrontendSettings()
    {
        return AjaxResult.success(publicFrontendSettings());
    }

    @GetMapping("/app/courses")
    public AjaxResult appCourses(@RequestParam Map<String, String> params)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> course : filteredCourses(params))
        {
            list.add(courseListItem(course));
        }
        return AjaxResult.success(list);
    }

    @GetMapping("/app/courses/{id}")
    public AjaxResult appCourse(@PathVariable String id, HttpServletRequest request)
    {
        Map<String, Object> course = findCourse(id);
        return course == null ? AjaxResult.error("课程不存在") : AjaxResult.success(courseForApp(course, currentUser(request)));
    }

    @GetMapping("/app/my/courses")
    public AjaxResult myCourses(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        refreshExpiredEnrollments();
        if (user == null)
        {
            return AjaxResult.success(Collections.emptyList());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> enrollment : enrollments)
        {
            if (!str(user.get("id")).equals(enrollment.get("userId")) || !isEnrollmentOpen(enrollment))
            {
                continue;
            }
            Map<String, Object> course = findCourse(str(enrollment.get("courseId")));
            if (course != null)
            {
                list.add(map(
                    "id", course.get("id"),
                    "title", stripCourseYear(course.get("courseName")),
                    "courseName", stripCourseYear(course.get("courseName")),
                    "sub", stripCourseYear(course.get("introduction")),
                    "expiry", enrollment.get("expiry"),
                    "cover", course.get("cover"),
                    "subject", course.get("subject"),
                    "kind", course.get("kind"),
                    "studentName", enrollment.get("studentName"),
                    "recentExamScore", enrollment.get("recentExamScore"),
                    "grade", enrollment.get("grade"),
                    "schoolName", enrollment.get("schoolName"),
                    "region", enrollment.get("region")
                ));
            }
        }
        return AjaxResult.success(list);
    }

    @GetMapping("/app/my/docs")
    public AjaxResult myDocs(@RequestParam(required = false, defaultValue = "") String kw,
                             @RequestParam(required = false, defaultValue = "") String courseId)
    {
        String text = kw.trim().toLowerCase();
        String expectedCourseId = str(courseId).trim();
        if (expectedCourseId.length() > 0)
        {
            expectedCourseId = scopedCourseId(expectedCourseId);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> doc : docs)
        {
            if (Boolean.FALSE.equals(doc.get("visible")))
            {
                continue;
            }
            if (expectedCourseId.length() > 0 && !expectedCourseId.equals(scopedCourseId(doc.get("courseId"))))
            {
                continue;
            }
            if (text.length() == 0 || str(doc.get("title")).toLowerCase().contains(text))
            {
                list.add(doc);
            }
        }
        return AjaxResult.success(list);
    }

    @GetMapping("/app/study/summary")
    public AjaxResult studySummary()
    {
        return AjaxResult.success(getStudySummary());
    }

    @GetMapping("/app/study/report")
    public AjaxResult studyReport(@RequestParam(required = false, defaultValue = "") String courseId,
                                  @RequestParam(required = false, defaultValue = "") String userId,
                                  HttpServletRequest request)
    {
        Map<String, Object> requester = currentUser(request);
        Map<String, Object> user = resolveStudyUser(requester, userId);
        List<Map<String, Object>> userAttempts = filterByUser(attempts, user);
        String courseTitle = "";
        Map<String, Object> course = findCourse(courseId);
        if (course != null)
        {
            courseTitle = stripCourseYear(course.get("courseName"));
        }
        int wrongCount = 0;
        for (Map<String, Object> wrong : filterByUser(wrongQuestions, user))
        {
            if (!Boolean.TRUE.equals(wrong.get("mastered")))
            {
                wrongCount++;
            }
        }
        int avg = 76;
        if (!userAttempts.isEmpty())
        {
            int total = 0;
            for (Map<String, Object> item : userAttempts)
            {
                total += intValue(item.get("score"));
            }
            avg = Math.round(total / (float) userAttempts.size());
        }
        int totalQuestions = 0;
        int correctQuestions = 0;
        for (Map<String, Object> item : userAttempts)
        {
            totalQuestions += intValue(item.get("total"));
            correctQuestions += intValue(item.get("correct"));
        }
        int accuracy = totalQuestions == 0 ? 0 : Math.round(correctQuestions * 100f / totalQuestions);
        List<Map<String, Object>> overview = list(
            map("label", "练习次数", "value", userAttempts.size() + "次"),
            map("label", "平均得分", "value", avg + "分"),
            map("label", "待复盘错题", "value", wrongCount + "道"),
            map("label", "累计学习", "value", studyDurationText(user, courseId))
        );
        List<Map<String, Object>> recentPractice = recentPracticeRows(userAttempts);
        List<Map<String, Object>> chapterSweep = attemptRowsByType(userAttempts, "quiz");
        Map<String, Object> learning = learningStats(user, courseId);
        List<String> suggestions = wrongCount > 0
            ? Arrays.asList("先复盘错题与巩固，再进入复习加强。", "每次练习后查看解析，记录易错概念。")
            : Collections.singletonList("当前错题较少，可以继续推进新章节。");
        Collections.reverse(userAttempts);
        return AjaxResult.success(map(
            "courseId", courseId,
            "courseTitle", courseTitle,
            "summary", getStudySummary(),
            "learningStats", learning,
            "overview", overview,
            "attempts", userAttempts.size() > 8 ? userAttempts.subList(0, 8) : userAttempts,
            "recentPractice", recentPractice,
            "practiceRows", recentPractice,
            "practiceCount", totalQuestions,
            "wrongCount", wrongCount,
            "accuracy", accuracy,
            "averageScore", avg,
            "chapterSweep", chapterSweep,
            "learningRecords", learning.get("records"),
            "suggestions", suggestions
        ));
    }

    @GetMapping("/app/study/plan")
    public AjaxResult studyPlan(@RequestParam(required = false, defaultValue = "gk-math-full") String courseId)
    {
        for (Map<String, Object> plan : studyPlans)
        {
            if (courseId.equals(plan.get("courseId")))
            {
                return AjaxResult.success(plan);
            }
        }
        return AjaxResult.success(studyPlans.get(0));
    }

    @GetMapping("/app/lesson/video")
    public AjaxResult lessonVideo(@RequestParam String lessonId, @RequestParam(required = false, defaultValue = "") String courseId, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        if (courseId.length() > 0)
        {
            Map<String, Object> course = findCourse(courseId);
            if (course != null && "full".equals(course.get("kind")) && !hasActiveEnrollment(user, courseId))
            {
                return AjaxResult.error("权限未开通，请联系授权");
            }
        }
        String videoUrl = SAMPLE_VIDEO_URL;
        String poster = "/static/courses/gk-shuxue-full-detail.jpg";
        Map<String, Object> course = findCourse(courseId);
        if (course != null)
        {
            String courseVideo = str(course.get("defaultVideoUrl")).trim();
            if (courseVideo.length() > 0)
            {
                videoUrl = courseVideo;
            }
            String coursePoster = str(course.get("detailCover")).trim();
            if (coursePoster.length() == 0)
            {
                coursePoster = str(course.get("cover")).trim();
            }
            if (coursePoster.length() > 0)
            {
                poster = coursePoster;
            }
            Map<String, Object> lessonMeta = findLessonMeta(lessonId, courseId);
            String lessonVideo = lessonMeta == null ? "" : str(lessonMeta.get("videoUrl")).trim();
            if (lessonVideo.length() > 0)
            {
                videoUrl = lessonVideo;
            }
        }
        Map<String, Object> data = map(
            "id", lessonId,
            "title", lessonId,
            "videoUrl", videoUrl,
            "poster", poster,
            "duration", 18,
            "pageTotal", 1,
            "prevTitle", lessonId.contains("奇偶") ? "1.集合逻辑不等式" : "",
            "nextTitle", lessonId.contains("集合") ? "2.奇偶性与单调性" : "下一讲",
            "card", map("title", "讲点卡", "items", Arrays.asList("先看题目条件，圈出关键词。", "写出对应知识点公式或定义。", "完成例题后进入真题讲练巩固。")),
            "progress", lessonProgress.get(progressKey(user, lessonId))
        );
        return AjaxResult.success(data);
    }

    @PostMapping("/app/lesson/progress")
    public AjaxResult lessonProgress(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        String lessonId = str(body.get("lessonId"));
        double duration = doubleValue(body.get("duration"));
        double currentTime = doubleValue(body.get("currentTime"));
        int percent = Boolean.TRUE.equals(body.get("ended")) ? 100 : intValue(body.get("percent"));
        if (percent == 0 && duration > 0)
        {
            percent = Math.round((float) (currentTime / duration * 100));
        }
        Map<String, Object> progress = map(
            "lessonId", lessonId,
            "userId", user == null ? null : user.get("id"),
            "lessonTitle", body.get("lessonTitle") == null ? lessonId : body.get("lessonTitle"),
            "courseId", body.get("courseId"),
            "courseTitle", body.get("courseTitle"),
            "currentTime", currentTime,
            "duration", duration,
            "percent", percent,
            "ended", Boolean.TRUE.equals(body.get("ended")),
            "updatedAt", now()
        );
        lessonProgress.put(progressKey(user, lessonId), progress);
        persistData();
        return AjaxResult.success(progress);
    }

    @GetMapping("/app/lesson/rating")
    public AjaxResult lessonRating(@RequestParam String lessonId, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        return AjaxResult.success(map("rating", findRating(lessonId, user)));
    }

    @PostMapping("/app/lesson/rating")
    public AjaxResult saveLessonRating(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        String lessonId = str(body.get("lessonId"));
        int rating = intValue(body.get("rating"));
        if (user == null)
        {
            return AjaxResult.error("请先登录后再评分");
        }
        Map<String, Object> progress = lessonProgress.get(progressKey(user, lessonId));
        if (progress == null || intValue(progress.get("percent")) < 90)
        {
            return AjaxResult.error("学习进度达到90%后才可以评分");
        }
        if (rating < 1 || rating > 5)
        {
            return AjaxResult.error("评分必须在 1 到 5 星之间");
        }
        Map<String, Object> meta = resolveRatingMeta(lessonId, body);
        Map<String, Object> existing = null;
        for (Map<String, Object> item : lessonRatings)
        {
            if (lessonId.equals(item.get("lessonId")) && sameUser(item, user))
            {
                existing = item;
                break;
            }
        }
        if (existing != null)
        {
            return AjaxResult.error("本节课程已评分，不能重复修改");
        }
        existing = map("id", "rating-" + System.currentTimeMillis());
        lessonRatings.add(existing);
        existing.put("lessonId", lessonId);
        existing.put("lessonTitle", body.get("lessonTitle") == null ? lessonId : body.get("lessonTitle"));
        existing.put("courseId", meta.get("courseId"));
        existing.put("courseTitle", meta.get("courseTitle"));
        existing.put("subjectTitle", meta.get("subjectTitle"));
        existing.put("chapterTitle", meta.get("chapterTitle"));
        existing.put("rating", rating);
        existing.put("userId", user == null ? null : user.get("id"));
        existing.put("userName", user == null ? "" : user.get("name"));
        existing.put("recentExamScore", recentExamScoreForUserCourse(user, str(meta.get("courseId"))));
        existing.put("updatedAt", now());
        persistData();
        return AjaxResult.success(map("lessonId", lessonId, "rating", rating));
    }

    @GetMapping("/app/practice")
    public AjaxResult practice(@RequestParam String title,
                               @RequestParam(required = false, defaultValue = "") String questionIds,
                               @RequestParam(required = false, defaultValue = "practice") String type,
                               @RequestParam(required = false, defaultValue = "") String courseId)
    {
        List<String> ids = commaList(questionIds);
        List<Map<String, Object>> source = ids.isEmpty() ? questionsFor(title) : questionsByIds(ids);
        if (shouldAutoDrawQuestions(type, title))
        {
            source = drawPaperQuestions(source, type);
            persistData();
        }
        return AjaxResult.success(map("title", title, "type", type, "courseId", scopedCourseId(courseId), "questions", publicQuestions(source)));
    }

    @PostMapping("/app/upload")
    public AjaxResult appUpload(MultipartFile file)
    {
        try
        {
            if (file == null || file.isEmpty())
            {
                return AjaxResult.error("请选择要上传的图片");
            }
            String fileName = FileUploadUtils.upload(RuoYiConfig.getUploadPath(), file);
            String url = serverConfig.getUrl() + fileName;
            return AjaxResult.success(map(
                "url", url,
                "fileName", fileName,
                "newFileName", FileUtils.getName(fileName),
                "originalFilename", file.getOriginalFilename()
            ));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PostMapping("/app/practice/submit")
    public AjaxResult submitPractice(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> result = grade(body, currentUser(request));
        persistData();
        return AjaxResult.success(result);
    }

    @PostMapping("/app/practice/self-review")
    public AjaxResult selfReviewPractice(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        String attemptId = str(body.get("attemptId"));
        String questionId = str(body.get("questionId"));
        Map<String, Object> attempt = findById(attempts, attemptId);
        if (attempt == null || !sameUser(attempt, user))
        {
            return AjaxResult.error("答题记录不存在");
        }
        Map<String, Object> detail = null;
        for (Map<String, Object> item : mapList(attempt.get("details")))
        {
            if (questionId.equals(str(item.get("id"))))
            {
                detail = item;
                break;
            }
        }
        if (detail == null)
        {
            return AjaxResult.error("题目记录不存在");
        }
        String reviewResult = normalizeReviewResult(body.get("reviewResult"), body.get("correct"));
        boolean correct = "correct".equals(reviewResult);
        boolean partial = "partial".equals(reviewResult);
        detail.put("manualReview", false);
        detail.put("selfReviewed", true);
        detail.put("correct", correct);
        detail.put("partialCredit", partial ? 0.5d : (correct ? 1d : 0d));
        detail.put("reviewedAt", now());
        detail.put("reviewResult", reviewResult);
        if (correct)
        {
            markWrongQuestionMastered(user, str(attempt.get("courseId")), questionId);
        }
        else
        {
            Map<String, Object> wrong = wrongFromReviewedDetail(attempt, detail, user);
            recordWrongQuestion(wrong, user);
        }
        recalculateAttemptScore(attempt);
        persistData();
        return AjaxResult.success(map("attempt", attempt, "detail", detail));
    }

    @GetMapping("/app/quiz")
    public AjaxResult quiz(@RequestParam String quizId,
                           @RequestParam(required = false, defaultValue = "") String courseId,
                           @RequestParam(required = false, defaultValue = "") String questionIds)
    {
        List<String> ids = commaList(questionIds);
        Map<String, Object> quiz = findCourseQuiz(quizId, courseId);
        if (ids.isEmpty() && quiz != null)
        {
            ids = stringList(quiz.get("questionIds"));
        }
        List<Map<String, Object>> source = ids.isEmpty() ? questionsFor(quizId) : questionsByIds(ids);
        source = drawPaperQuestions(source, "quiz");
        persistData();
        return AjaxResult.success(map("id", quizId, "title", quizId, "type", "quiz", "quiz", quiz, "questions", publicQuestions(source)));
    }

    @PostMapping("/app/quiz/submit")
    public AjaxResult submitQuiz(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        body.put("type", "quiz");
        Map<String, Object> result = grade(body, currentUser(request));
        persistData();
        return AjaxResult.success(result);
    }

    @GetMapping("/app/wrongbook")
    public AjaxResult wrongbook(@RequestParam(required = false, defaultValue = "") String source,
                                @RequestParam(required = false, defaultValue = "") String courseId,
                                HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        return AjaxResult.success(wrongbookItems(filterByCourse(filterByUser(wrongQuestions, user), courseId), source));
    }

    @GetMapping("/app/wrongbook/summary")
    public AjaxResult wrongbookSummary(@RequestParam(required = false, defaultValue = "gk-math-full") String courseId, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        List<Map<String, Object>> list = filterByCourse(filterByUser(wrongQuestions, user), courseId);
        List<Map<String, Object>> records = attemptRecords(filterByCourse(filterByUser(attempts, user), courseId), "");
        Map<String, Object> course = findCourse(courseId);
        return AjaxResult.success(map(
            "total", list.size(),
            "latest", countLatestWrong(list),
            "latestAt", latestWrongAt(list),
            "pending", countWrongByMastered(list, false),
            "mastered", countWrongByMastered(list, true),
            "weak", weakWrongItems(list, "").size(),
            "recordTotal", records.size(),
            "sourceStats", wrongSourceStats(list),
            "course", course == null ? new LinkedHashMap<String, Object>() : courseForApp(course, user)
        ));
    }

    @GetMapping("/app/wrongbook/records")
    public AjaxResult wrongbookRecords(@RequestParam(required = false, defaultValue = "") String source,
                                       @RequestParam(required = false, defaultValue = "") String courseId,
                                       HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        List<Map<String, Object>> records = attemptRecords(filterByCourse(filterByUser(attempts, user), courseId), source);
        return AjaxResult.success(map(
            "total", records.size(),
            "courseCounts", attemptCourseCounts(records),
            "records", records
        ));
    }

    @GetMapping("/app/wrongbook/weak")
    public AjaxResult weakWrongbook(@RequestParam(required = false, defaultValue = "") String source,
                                    @RequestParam(required = false, defaultValue = "") String courseId,
                                    HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        return AjaxResult.success(weakWrongItems(filterByCourse(filterByUser(wrongQuestions, user), courseId), source));
    }

    @GetMapping("/app/wrongbook/retry")
    public AjaxResult wrongRetry(@RequestParam(required = false, defaultValue = "5") int count,
                                 @RequestParam(required = false, defaultValue = "") String source,
                                 @RequestParam(required = false, defaultValue = "") String courseId,
                                 HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        return AjaxResult.success(wrongRetryPaper(filterByCourse(filterByUser(wrongQuestions, user), courseId), count, source));
    }

    @PostMapping("/app/wrongbook/mastered")
    public AjaxResult markWrong(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        String id = str(body.get("id"));
        for (Map<String, Object> wrong : wrongQuestions)
        {
            if (id.equals(wrong.get("id")) && sameUser(wrong, user))
            {
                wrong.put("mastered", true);
                wrong.put("updatedAt", now());
                persistData();
                return AjaxResult.success(wrong);
            }
        }
        return AjaxResult.success(new LinkedHashMap<String, Object>());
    }

    @GetMapping("/app/favorites")
    public AjaxResult favoriteList(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        List<Map<String, Object>> courseItems = new ArrayList<>();
        List<Map<String, Object>> questionItems = new ArrayList<>();
        for (Map<String, Object> favorite : favorites)
        {
            if (!sameUser(favorite, user))
            {
                continue;
            }
            if ("course".equals(favorite.get("type")))
            {
                Map<String, Object> course = findCourse(str(favorite.get("targetId")));
                Map<String, Object> enrollment = findEnrollment(user, str(favorite.get("targetId")));
                Map<String, Object> item = new LinkedHashMap<>(favorite);
                if (course != null)
                {
                item.put("title", stripCourseYear(course.get("courseName")));
                    item.put("cover", course.get("cover"));
                    item.put("subject", course.get("subject"));
                    item.put("kind", course.get("kind"));
                }
                item.put("expiry", enrollment == null ? "" : enrollment.get("expiry"));
                item.put("available", enrollment != null && isEnrollmentOpen(enrollment));
                courseItems.add(item);
            }
            else if ("question".equals(favorite.get("type")))
            {
                Map<String, Object> question = findById(questions, str(favorite.get("targetId")));
                if (question != null)
                {
                    Map<String, Object> item = new LinkedHashMap<>(question);
                    item.put("favoriteId", favorite.get("id"));
                    item.put("createdAt", favorite.get("createdAt"));
                    questionItems.add(item);
                }
            }
        }
        return AjaxResult.success(map("courses", courseItems, "questions", questionItems));
    }

    @PostMapping("/app/favorites/toggle")
    public AjaxResult toggleFavorite(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        if (user == null)
        {
            return AjaxResult.error("请先登录");
        }
        String type = str(body.get("type"));
        String targetId = str(body.get("targetId"));
        if (type.length() == 0 || targetId.length() == 0)
        {
            return AjaxResult.error("收藏参数不完整");
        }
        for (int i = favorites.size() - 1; i >= 0; i--)
        {
            Map<String, Object> item = favorites.get(i);
            if (type.equals(item.get("type")) && targetId.equals(item.get("targetId")) && sameUser(item, user))
            {
                if ("add".equals(str(body.get("action"))))
                {
                    return AjaxResult.success(map("favorited", true, "favorite", item));
                }
                favorites.remove(i);
                persistData();
                return AjaxResult.success(map("favorited", false));
            }
        }
        Map<String, Object> favorite = map(
            "id", "fav-" + System.currentTimeMillis(),
            "userId", user.get("id"),
            "userName", user.get("name"),
            "type", type,
            "targetId", targetId,
            "title", body.get("title"),
            "courseId", body.get("courseId"),
            "createdAt", now()
        );
        favorites.add(favorite);
        persistData();
        return AjaxResult.success(map("favorited", true, "favorite", favorite));
    }

    @PostMapping("/app/favorites/question/answer")
    public AjaxResult answerFavoriteQuestion(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        String questionId = str(body.get("questionId"));
        Map<String, Object> question = findById(questions, questionId);
        if (question == null)
        {
            return AjaxResult.error("题目不存在");
        }
        Object submitted = body.containsKey("answer") ? body.get("answer") : body.get("selected");
        String questionType = questionType(question);
        boolean manualReview = isSelfReviewQuestion(question);
        int selected = isChoiceQuestion(question) ? intValue(submitted) : -1;
        String selectedText = isChoiceQuestion(question) ? optionText(question, selected) : str(submitted).trim();
        String answerText = correctAnswerText(question);
        boolean correct = !manualReview && questionAnswerMatches(question, submitted);
        Map<String, Object> attempt = map(
            "id", "attempt-" + System.currentTimeMillis(),
            "userId", user == null ? null : user.get("id"),
            "title", "我的收藏",
            "type", "favorite",
            "total", 1,
            "correct", correct ? 1 : 0,
            "manualReviewCount", manualReview ? 1 : 0,
            "score", correct ? 100 : 0,
            "createdAt", now(),
            "details", list(map("id", questionId, "stem", question.get("stem"), "questionType", questionType, "selected", isChoiceQuestion(question) ? selected : selectedText, "selectedText", selectedText, "answer", isChoiceQuestion(question) ? question.get("answer") : answerText, "answerText", answerText, "answerImageUrl", str(question.get("answerImageUrl")), "correct", correct, "manualReview", manualReview, "selfReviewed", !manualReview, "analysis", question.get("analysis"), "analysisImageUrl", analysisImageUrl(question), "videoAnalysisUrl", analysisVideoUrl(question)))
        );
        attempts.add(attempt);
        if (!correct && !manualReview)
        {
            recordWrongQuestion(map(
                "id", "wrong-" + System.currentTimeMillis(),
                "userId", user == null ? null : user.get("id"),
                "questionId", question.get("id"),
                "questionType", questionType,
                "title", "我的收藏",
                "type", "favorite",
                "stem", question.get("stem"),
                "options", question.get("options"),
                "answer", question.get("answer"),
                "answerText", answerText,
                "selected", selected,
                "selectedText", selectedText,
                "analysis", question.get("analysis"),
                "analysisImageUrl", analysisImageUrl(question),
                "videoAnalysisUrl", analysisVideoUrl(question),
                "knowledge", question.get("knowledge"),
                "mastered", false,
                "updatedAt", now()
            ), user);
        }
        persistData();
        return AjaxResult.success(map("correct", correct, "manualReview", manualReview, "questionType", questionType, "answer", isChoiceQuestion(question) ? question.get("answer") : answerText, "answerText", answerText, "selectedText", selectedText, "analysis", question.get("analysis"), "analysisImageUrl", analysisImageUrl(question), "videoAnalysisUrl", analysisVideoUrl(question), "attempt", attempt));
    }

    @GetMapping("/app/my/students")
    public AjaxResult myStudents(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        if (user == null)
        {
            return AjaxResult.success(Collections.emptyList());
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> binding : studentBindings)
        {
            if (str(user.get("id")).equals(str(binding.get("ownerUserId"))))
            {
                String studentUserId = str(binding.get("studentUserId"));
                Map<String, Object> student = findById(users, studentUserId);
                if (student != null)
                {
                    Map<String, Object> item = publicUser(student);
                    item.put("learning", studentLearningSnapshot(studentUserId));
                    item.putAll(studentCourseSummary(studentUserId));
                    item.put("bindingId", binding.get("id"));
                    item.put("bindingCreatedAt", binding.get("createdAt"));
                    item.put("bindingSource", "账号绑定");
                    item.put("relationType", "account");
                    item.put("permissionText", "只读查看学情");
                    item.put("canEditLearning", false);
                    item.put("canUnbind", true);
                    result.put(studentUserId, item);
                }
            }
        }
        for (Map<String, Object> student : users)
        {
            String studentUserId = str(student.get("id"));
            if (studentUserId.length() == 0 || result.containsKey(studentUserId))
            {
                continue;
            }
            if (str(user.get("id")).equals(str(student.get("referrerUserId"))))
            {
                Map<String, Object> item = publicUser(student);
                item.put("learning", studentLearningSnapshot(studentUserId));
                item.putAll(studentCourseSummary(studentUserId));
                item.put("bindingId", "referrer-" + studentUserId);
                item.put("bindingCreatedAt", student.get("referrerBoundAt"));
                item.put("bindingSource", "推荐绑定");
                item.put("relationType", "referrer");
                item.put("permissionText", "只读查看学情");
                item.put("canEditLearning", false);
                item.put("canUnbind", false);
                result.put(studentUserId, item);
            }
        }
        if ("agency_admin".equals(user.get("role")) || "admin".equals(user.get("role")))
        {
            for (Map<String, Object> order : orders)
            {
                if (str(user.get("id")).equals(str(order.get("agencyUserId"))) || str(user.get("id")).equals(str(order.get("ownerUserId"))))
                {
                    String studentUserId = str(order.get("userId"));
                    if (studentUserId.length() == 0 || result.containsKey(studentUserId))
                    {
                        continue;
                    }
                    Map<String, Object> item = map("id", studentUserId, "name", order.get("studentName"), "phone", "", "grade", order.get("grade"), "region", order.get("region"), "learning", studentLearningSnapshot(studentUserId));
                    item.putAll(studentCourseSummary(studentUserId));
                    item.put("bindingId", "");
                    item.put("bindingCreatedAt", order.get("createdAt"));
                    item.put("bindingSource", "授权记录");
                    item.put("relationType", "authorization");
                    item.put("permissionText", "只读查看学情");
                    item.put("canEditLearning", false);
                    item.put("canUnbind", false);
                    result.put(studentUserId, item);
                }
            }
        }
        return AjaxResult.success(new ArrayList<>(result.values()));
    }

    @PostMapping("/app/my/students/bind")
    public AjaxResult bindStudent(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> owner = currentUser(request);
        if (owner == null)
        {
            return AjaxResult.error("请先登录");
        }
        String phone = str(body.get("phone")).trim();
        String password = str(body.get("password"));
        String smsCode = str(body.get("smsCode")).trim();
        Map<String, Object> student = null;
        for (Map<String, Object> user : users)
        {
            if (phone.equals(user.get("phone")))
            {
                student = user;
                break;
            }
        }
        if (student == null)
        {
            return AjaxResult.error("学生账号不存在");
        }
        if (!password.equals(student.get("password")) && !smsCode.equals(verificationCodes.get(phone)))
        {
            return AjaxResult.error("请输入学生登录密码或短信验证码");
        }
        for (Map<String, Object> binding : studentBindings)
        {
            if (owner.get("id").equals(binding.get("ownerUserId")) && student.get("id").equals(binding.get("studentUserId")))
            {
                return AjaxResult.success(binding);
            }
        }
        Map<String, Object> binding = map("id", "stu-bind-" + System.currentTimeMillis(), "ownerUserId", owner.get("id"), "studentUserId", student.get("id"), "createdAt", now());
        studentBindings.add(binding);
        persistData();
        return AjaxResult.success(binding);
    }

    @PostMapping("/app/my/students/unbind")
    public AjaxResult unbindStudent(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> owner = currentUser(request);
        if (owner == null)
        {
            return AjaxResult.error("请先登录");
        }
        String studentUserId = str(body.get("studentUserId")).trim();
        boolean removed = studentBindings.removeIf(binding ->
            owner.get("id").equals(binding.get("ownerUserId")) && studentUserId.equals(str(binding.get("studentUserId")))
        );
        if (removed)
        {
            persistData();
        }
        return AjaxResult.success(map("removed", removed));
    }

    @GetMapping("/app/my/referrer")
    public AjaxResult myReferrer(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        if (user == null)
        {
            return AjaxResult.success(new LinkedHashMap<String, Object>());
        }
        Map<String, Object> referrer = findById(users, str(user.get("referrerUserId")));
        return AjaxResult.success(referrer == null ? new LinkedHashMap<String, Object>() : publicUser(referrer));
    }

    @PostMapping("/app/my/referrer/bind")
    public AjaxResult bindReferrer(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        if (user == null)
        {
            return AjaxResult.error("请先登录");
        }
        String phone = str(body.get("phone")).trim();
        String referrerId = str(body.get("referrerId")).trim();
        Map<String, Object> referrer = findById(users, referrerId);
        if (referrer == null || !phone.equals(str(referrer.get("phone"))))
        {
            return AjaxResult.error("推荐人手机号或 ID 不匹配");
        }
        if (str(user.get("id")).equals(referrerId))
        {
            return AjaxResult.error("不能绑定自己为推荐人");
        }
        String currentReferrerId = str(user.get("referrerUserId"));
        if (currentReferrerId.length() > 0)
        {
            if (currentReferrerId.equals(referrerId))
            {
                return AjaxResult.success(publicUser(referrer));
            }
            return AjaxResult.error("已绑定推荐人，不能重复更换");
        }
        user.put("referrerUserId", referrerId);
        user.put("referrerBoundAt", now());
        persistData();
        return AjaxResult.success(publicUser(referrer));
    }

    @GetMapping("/app/reinforce")
    public AjaxResult reinforce(@RequestParam(required = false, defaultValue = "gk-math-full") String courseId)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean changed = false;
        int index = 0;
        for (Map<String, Object> item : reinforcePoints)
        {
            if (courseId.equals(item.get("courseId")))
            {
                if (!item.containsKey("testCount"))
                {
                    item.put("testCount", defaultReinforceTestCount(index));
                    changed = true;
                }
                list.add(item);
                index++;
            }
        }
        if (changed)
        {
            persistData();
        }
        return AjaxResult.success(list);
    }

    @GetMapping("/app/reinforce/practice")
    public AjaxResult reinforcePractice(@RequestParam String pointId)
    {
        Map<String, Object> point = reinforcePoints.isEmpty() ? null : reinforcePoints.get(0);
        for (Map<String, Object> item : reinforcePoints)
        {
            if (pointId.equals(item.get("id")))
            {
                point = item;
                break;
            }
        }
        List<String> ids = stringList(point.get("questionIds"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> q : questions)
        {
            if (ids.contains(q.get("id")))
            {
                list.add(q);
            }
        }
        return AjaxResult.success(map("title", point.get("title"), "type", "reinforce", "point", point, "questions", publicQuestions(list)));
    }

    private static int defaultReinforceTestCount(int index)
    {
        int[] defaults = {3, 5, 2};
        return defaults[Math.min(Math.max(index, 0), defaults.length - 1)];
    }

    @PostMapping("/app/ai/ask")
    public AjaxResult askAi(@RequestBody Map<String, Object> body)
    {
        String message = str(body.get("message"));
        String context = str(body.get("context"));
        Map<String, Object> chat = map(
            "id", "chat-" + System.currentTimeMillis(),
            "context", context,
            "message", message,
            "reply", "围绕“" + (context.length() == 0 ? "当前课程" : context) + "”，建议你先定位题目条件，再写出核心公式。你的问题是：" + message + "。可以把卡住的步骤发给我，我会继续拆解。",
            "createdAt", now()
        );
        aiChats.add(chat);
        persistData();
        return AjaxResult.success(chat);
    }

    @PostMapping("/app/authorization/apply")
    public AjaxResult applyAuthorization(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        Map<String, Object> item = map(
            "id", "req-" + System.currentTimeMillis(),
            "userId", user == null ? null : user.get("id"),
            "userName", user == null ? "" : user.get("name"),
            "courseId", body.get("courseId"),
            "note", body.get("note") == null ? "申请授权" : body.get("note"),
            "status", "pending",
            "createdAt", now()
        );
        authRequests.add(item);
        persistData();
        return AjaxResult.success(item);
    }

    @PostMapping("/app/orders")
    public AjaxResult createOrder(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        return AjaxResult.error("课程开通请使用激活码验证");
    }

    @PostMapping("/app/activate")
    public AjaxResult activate(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        String code = normalizeCardCode(body.get("code"));
        String studentName = str(body.get("studentName")).trim();
        String gender = str(body.get("gender")).trim();
        String recentExamScore = str(body.get("recentExamScore")).trim();
        String grade = str(body.get("grade")).trim();
        String schoolName = str(body.get("schoolName")).trim();
        String region = str(body.get("region")).trim();
        if (code.length() == 0 || studentName.length() == 0 || gender.length() == 0 || recentExamScore.length() == 0 || grade.length() == 0 || schoolName.length() == 0 || region.length() == 0)
        {
            return AjaxResult.error("请填写激活码、学生名字、性别、科目最近考试分数、年级、学校名字和所在地区");
        }
        Map<String, Object> card = findActivationCode(code);
        if (card == null)
        {
            return AjaxResult.error("激活码无效");
        }
        if (!"available".equals(card.get("status")) || activationCardLocked(card))
        {
            return AjaxResult.error("激活码已使用或已关闭");
        }
        String courseId = str(body.get("courseId")).trim();
        if (courseId.length() == 0)
        {
            courseId = str(card.get("courseId")).trim();
        }
        if (courseId.length() == 0)
        {
            return AjaxResult.error("请选择要开通的课程");
        }
        if (findCourse(courseId) == null)
        {
            return AjaxResult.error("课程不存在");
        }
        String expiry = expiryForCard(card);
        Map<String, Object> user = currentUser(request);
        Map<String, Object> order = createOrderRecord(courseId, user, "激活码开通", code, expiry, str(card.get("cardType")));
        order.put("studentName", studentName);
        order.put("gender", gender);
        order.put("recentExamScore", recentExamScore);
        order.put("grade", grade);
        order.put("schoolName", schoolName);
        order.put("region", region);
        order.put("expiresAt", expiry);
        order.put("cardType", card.get("cardType"));
        updateEnrollmentFromOrder(order);
        card.put("status", "used");
        card.put("locked", false);
        card.put("authorizationClosed", false);
        card.put("courseId", courseId);
        card.put("usedByUserId", user == null ? null : user.get("id"));
        card.put("usedByName", user == null ? "" : user.get("name"));
        card.put("studentName", studentName);
        card.put("gender", gender);
        card.put("recentExamScore", recentExamScore);
        card.put("grade", grade);
        card.put("schoolName", schoolName);
        card.put("region", region);
        card.put("activatedAt", now());
        card.put("expiresAt", expiry);
        persistData();
        return AjaxResult.success(order);
    }

    @GetMapping("/admin/dashboard")
    public AjaxResult dashboard()
    {
        return AjaxResult.success(map(
            "courseTotal", courses.size(),
            "courseStats", courseKindStats(),
            "userTotal", users.size(),
            "userStats", userStats(),
            "orderTotal", orders.size(),
            "authPending", countStatus(authRequests, "pending"),
            "activationStats", activationStats(),
            "agencyStats", agencyStats(),
            "wrongTotal", wrongQuestions.size(),
            "attemptTotal", attempts.size(),
            "ratingStats", ratingStats(),
            "recentOrders", tail(orders, 6),
            "recentAuthRequests", tail(authRequests, 6)
        ));
    }

    @GetMapping("/admin/settings")
    public AjaxResult adminFrontendSettings()
    {
        return AjaxResult.success(copyFrontendSettings());
    }

    @PostMapping("/admin/settings")
    public AjaxResult saveAdminFrontendSettings(@RequestBody Map<String, Object> body)
    {
        synchronized (storeLock)
        {
            frontendSettings.clear();
            frontendSettings.putAll(normalizeFrontendSettings(body));
            logOperation("前端配置", "后台管理员", "首页与协议", "保存前端展示配置", "已完成");
            persistData();
            return AjaxResult.success(copyFrontendSettings());
        }
    }

    @GetMapping("/admin/courses")
    public AjaxResult adminCourses(@RequestParam Map<String, String> params)
    {
        return AjaxResult.success(filteredCourses(params));
    }

    @PostMapping("/admin/courses")
    public AjaxResult addCourse(@RequestBody Map<String, Object> course)
    {
        if (str(course.get("id")).length() == 0)
        {
            course.put("id", "course-" + System.currentTimeMillis());
        }
        ensureCourseDefaults(course);
        courses.add(course);
        logOperation("课程管理", "后台管理员", course.get("courseName"), "新增课程", "已完成");
        persistData();
        return AjaxResult.success(course);
    }

    @PutMapping("/admin/courses/{id}")
    public AjaxResult updateCourse(@PathVariable String id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> course = findCourse(id);
        if (course == null)
        {
            return AjaxResult.error("课程不存在");
        }
        course.putAll(body);
        course.put("id", id);
        ensureCourseDefaults(course);
        logOperation("课程管理", "后台管理员", course.get("courseName"), "编辑课程", "已完成");
        persistData();
        return AjaxResult.success(course);
    }

    @DeleteMapping("/admin/courses/{id}")
    public AjaxResult deleteCourse(@PathVariable String id)
    {
        Map<String, Object> course = findCourse(id);
        courses.removeIf(item -> id.equals(item.get("id")));
        logOperation("课程管理", "后台管理员", course == null ? id : course.get("courseName"), "删除课程", "已完成");
        persistData();
        return AjaxResult.success();
    }

    @GetMapping("/admin/docs")
    public AjaxResult adminDocs()
    {
        boolean changed = false;
        for (Map<String, Object> doc : docs)
        {
            changed |= ensureDocDefaults(doc);
        }
        if (changed)
        {
            persistData();
        }
        return AjaxResult.success(docs);
    }

    @PostMapping("/admin/docs")
    public AjaxResult addDoc(@RequestBody Map<String, Object> doc)
    {
        if (str(doc.get("id")).length() == 0)
        {
            doc.put("id", "doc-" + System.currentTimeMillis());
        }
        if (str(doc.get("uploadTime")).length() == 0)
        {
            doc.put("uploadTime", now());
        }
        ensureDocDefaults(doc);
        docs.add(doc);
        logOperation("资料管理", "后台管理员", doc.get("title"), "新增资料", "已完成");
        persistData();
        return AjaxResult.success(doc);
    }

    @PutMapping("/admin/docs/{id}")
    public AjaxResult updateDoc(@PathVariable String id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> doc = findById(docs, id);
        if (doc == null)
        {
            return AjaxResult.error("资料不存在");
        }
        doc.putAll(body);
        doc.put("id", id);
        ensureDocDefaults(doc);
        logOperation("资料管理", "后台管理员", doc.get("title"), "编辑资料", "已完成");
        persistData();
        return AjaxResult.success(doc);
    }

    @DeleteMapping("/admin/docs/{id}")
    public AjaxResult deleteDoc(@PathVariable String id)
    {
        Map<String, Object> doc = findById(docs, id);
        docs.removeIf(item -> id.equals(item.get("id")));
        logOperation("资料管理", "后台管理员", doc == null ? id : doc.get("title"), "删除资料", "已完成");
        persistData();
        return AjaxResult.success();
    }

    @GetMapping("/admin/questions")
    public AjaxResult adminQuestions()
    {
        for (Map<String, Object> question : questions)
        {
            normalizeQuestion(question);
        }
        return AjaxResult.success(questions);
    }

    @PostMapping("/admin/questions")
    public AjaxResult addQuestion(@RequestBody Map<String, Object> question)
    {
        normalizeQuestion(question);
        if (str(question.get("id")).length() == 0)
        {
            question.put("id", "q-" + System.currentTimeMillis());
        }
        questions.add(question);
        logOperation("题库管理", "后台管理员", str(question.get("subjectName")), "新增题目：" + str(question.get("stem")), "已完成");
        persistData();
        return AjaxResult.success(question);
    }

    @PutMapping("/admin/questions/{id}")
    public AjaxResult updateQuestion(@PathVariable String id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> question = findById(questions, id);
        if (question == null)
        {
            return AjaxResult.error("题目不存在");
        }
        normalizeQuestion(body);
        question.putAll(body);
        question.put("id", id);
        logOperation("题库管理", "后台管理员", str(question.get("subjectName")), "编辑题目：" + str(question.get("stem")), "已完成");
        persistData();
        return AjaxResult.success(question);
    }

    @DeleteMapping("/admin/questions/{id}")
    public AjaxResult deleteQuestion(@PathVariable String id)
    {
        Map<String, Object> question = findById(questions, id);
        questions.removeIf(item -> id.equals(item.get("id")));
        logOperation("题库管理", "后台管理员", question == null ? "" : str(question.get("subjectName")), "删除题目：" + (question == null ? id : str(question.get("stem"))), "已完成");
        persistData();
        return AjaxResult.success();
    }

    @GetMapping("/admin/users")
    public AjaxResult adminUsers()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> user : users)
        {
            list.add(publicUser(user));
        }
        return AjaxResult.success(list);
    }

    @PutMapping("/admin/users/{id}/role")
    public AjaxResult updateUserRole(@PathVariable String id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> user = findById(users, id);
        if (user == null)
        {
            return AjaxResult.error("用户不存在");
        }
        String role = str(body.get("role")).trim();
        if (role.length() == 0)
        {
            role = "student";
        }
        user.put("role", role);
        user.put("organizationName", str(body.get("organizationName")).trim());
        user.put("activationQuota", "agency_admin".equals(role) ? intValue(body.get("activationQuota")) : 0);
        putIfPresent(user, body, "name");
        putIfPresent(user, body, "phone");
        putIfPresent(user, body, "status");
        putIfPresent(user, body, "gender");
        putIfPresent(user, body, "grade");
        putIfPresent(user, body, "schoolName");
        putIfPresent(user, body, "region");
        putIfPresent(user, body, "recentExamScore");
        putIfPresent(user, body, "remark");
        if (body.containsKey("expiresAt"))
        {
            updateUserCourseExpiry(id, str(body.get("expiresAt")).trim());
        }
        if ("agency_admin".equals(role) && str(user.get("agencyId")).length() == 0)
        {
            user.put("agencyId", user.get("id"));
        }
        if (!"agency_admin".equals(role))
        {
            user.remove("agencyId");
        }
        logOperation("用户管理", user.get("name"), "", "编辑用户信息/角色：" + role, "已完成");
        persistData();
        return AjaxResult.success(publicUser(user));
    }

    @GetMapping("/admin/activation-codes")
    public AjaxResult adminActivationCodes()
    {
        enrichActivationCodes();
        return AjaxResult.success(activationCodes);
    }

    @PostMapping("/admin/activation-codes")
    public AjaxResult addActivationCode(@RequestBody Map<String, Object> body)
    {
        String code = normalizeCardCode(body.get("code"));
        if (code.length() == 0)
        {
            code = generateCardCode(str(body.get("cardType")));
        }
        else if (!isValidGeneratedCardCode(code))
        {
            return AjaxResult.error("激活码需为9位以上小写字母和数字组合");
        }
        if (findActivationCode(code) != null)
        {
            return AjaxResult.error("激活码已存在");
        }
        String ownerUserId = str(body.get("ownerUserId")).trim();
        if (ownerUserId.length() > 0 && findById(users, ownerUserId) == null)
        {
            return AjaxResult.error("归属账号不存在");
        }
        Map<String, Object> card = activationCode(
            code,
            str(body.get("courseId")).trim(),
            str(body.get("cardType")).length() == 0 ? "year" : str(body.get("cardType")),
            ownerUserId
        );
        card.put("remark", str(body.get("remark")));
        activationCodes.add(card);
        logOperation("激活码管理", card.get("ownerName"), card.get("courseTitle"), "新增激活码：" + code, "已完成");
        persistData();
        return AjaxResult.success(card);
    }

    @PutMapping("/admin/activation-codes/{id}")
    public AjaxResult updateActivationCode(@PathVariable String id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> card = findById(activationCodes, id);
        if (card == null)
        {
            return AjaxResult.error("激活码不存在");
        }
        normalizeActivationCardState(card);
        boolean used = "used".equals(card.get("status"));
        if (body.containsKey("locked"))
        {
            card.put("locked", boolValue(body.get("locked"), activationCardLocked(card)));
        }
        String status = str(body.get("status")).trim();
        if (status.length() > 0)
        {
            if ("disabled".equals(status))
            {
                card.put("locked", true);
            }
            else if ("available".equals(status) && !used)
            {
                card.put("status", "available");
                card.put("locked", false);
            }
            else if ("used".equals(status) && used)
            {
                card.put("locked", false);
            }
        }
        if (used)
        {
            if (body.containsKey("ownerUserId") || body.containsKey("courseId") || body.containsKey("cardType") || body.containsKey("remark"))
            {
                return AjaxResult.error("已使用激活码只能锁定/解锁或关闭课程授权");
            }
        }
        else
        {
            if (body.containsKey("ownerUserId"))
            {
                String currentOwner = str(card.get("ownerUserId")).trim();
                String nextOwner = str(body.get("ownerUserId")).trim();
                if (nextOwner.length() > 0)
                {
                    Map<String, Object> owner = findById(users, nextOwner);
                    if (owner == null)
                    {
                        return AjaxResult.error("归属账号不存在");
                    }
                    if (currentOwner.length() > 0 && !currentOwner.equals(nextOwner))
                    {
                        return AjaxResult.error("已分配激活码不能重新分配，请先取消分配");
                    }
                }
                putIfPresent(card, body, "ownerUserId");
            }
            putIfPresent(card, body, "courseId");
            putIfPresent(card, body, "cardType");
            putIfPresent(card, body, "remark");
        }
        enrichActivationCard(card);
        card.put("updatedAt", now());
        logOperation("激活码管理", card.get("ownerName"), card.get("courseTitle"), "编辑激活码：" + card.get("code"), str(card.get("status")));
        persistData();
        return AjaxResult.success(card);
    }

    @DeleteMapping("/admin/activation-codes/{id}")
    public AjaxResult deleteActivationCode(@PathVariable String id)
    {
        Map<String, Object> card = findById(activationCodes, id);
        if (card == null)
        {
            return AjaxResult.error("激活码不存在");
        }
        normalizeActivationCardState(card);
        if ("used".equals(card.get("status")))
        {
            return AjaxResult.error("已使用激活码不能删除");
        }
        activationCodes.remove(card);
        logOperation("激活码管理", card.get("ownerName"), card.get("courseTitle"), "删除激活码：" + card.get("code"), "已完成");
        persistData();
        return AjaxResult.success();
    }

    @PutMapping("/admin/activation-codes/{id}/close-authorization")
    public AjaxResult closeActivationCodeAuthorization(@PathVariable String id)
    {
        Map<String, Object> card = findById(activationCodes, id);
        if (card == null)
        {
            return AjaxResult.error("激活码不存在");
        }
        normalizeActivationCardState(card);
        if (!"used".equals(card.get("status")))
        {
            return AjaxResult.error("未使用激活码没有课程授权可关闭");
        }
        Map<String, Object> order = latestOrderForCard(str(card.get("code")));
        if (order == null)
        {
            return AjaxResult.error("未找到对应开通记录");
        }
        order.put("status", "closed");
        order.put("closedAt", now());
        closeEnrollmentForOrder(order);
        card.put("authorizationClosed", true);
        card.put("authorizationClosedAt", now());
        logOperation("激活码管理", card.get("usedByName"), card.get("courseTitle"), "关闭激活码课程授权：" + card.get("code"), "已关闭");
        persistData();
        return AjaxResult.success(card);
    }

    @PostMapping("/admin/activate")
    public AjaxResult adminActivate(@RequestBody Map<String, Object> body)
    {
        Map<String, Object> user = findById(users, str(body.get("userId")));
        if (user == null)
        {
            return AjaxResult.error("用户不存在");
        }
        String code = normalizeCardCode(body.get("code"));
        Map<String, Object> card = findActivationCode(code);
        if (card == null)
        {
            return AjaxResult.error("激活码无效");
        }
        if (!"available".equals(card.get("status")))
        {
            return AjaxResult.error("激活码已使用或已锁定，不能激活");
        }
        String courseId = str(body.get("courseId")).trim();
        if (courseId.length() == 0)
        {
            courseId = str(card.get("courseId"));
        }
        if (courseId.length() == 0)
        {
            return AjaxResult.error("请选择要开通的课程");
        }
        if (findCourse(courseId) == null)
        {
            return AjaxResult.error("课程不存在");
        }
        String expiry = expiryForCard(card);
        Map<String, Object> order = createOrderRecord(courseId, user, "后台激活码开通", code, expiry, str(card.get("cardType")));
        order.put("studentName", valueOrDefault(body.get("studentName"), user.get("name")));
        order.put("gender", valueOrDefault(body.get("gender"), user.get("gender")));
        order.put("recentExamScore", str(body.get("recentExamScore")));
        order.put("grade", valueOrDefault(body.get("grade"), user.get("grade")));
        order.put("schoolName", str(body.get("schoolName")));
        order.put("region", valueOrDefault(body.get("region"), user.get("region")));
        order.put("expiresAt", expiry);
        order.put("cardType", card.get("cardType"));
        updateEnrollmentFromOrder(order);
        card.put("status", "used");
        card.put("locked", false);
        card.put("authorizationClosed", false);
        card.put("usedByUserId", user.get("id"));
        card.put("usedByName", user.get("name"));
        card.put("courseId", courseId);
        putIfPresent(card, card, "courseId");
        card.put("studentName", order.get("studentName"));
        card.put("gender", order.get("gender"));
        card.put("recentExamScore", order.get("recentExamScore"));
        card.put("grade", order.get("grade"));
        card.put("schoolName", order.get("schoolName"));
        card.put("region", order.get("region"));
        card.put("activatedAt", now());
        card.put("expiresAt", expiry);
        logOperation("授权开通", user.get("name"), order.get("courseTitle"), "后台激活码开通：" + code, "已开通");
        persistData();
        return AjaxResult.success(order);
    }

    @GetMapping("/admin/agencies/{id}/summary")
    public AjaxResult agencySummary(@PathVariable String id)
    {
        return AjaxResult.success(agencySummaryData(id));
    }

    @GetMapping("/admin/agencies/summary")
    public AjaxResult agenciesSummary()
    {
        return AjaxResult.success(agencyStats());
    }

    @GetMapping("/admin/auth-requests")
    public AjaxResult adminAuthRequests()
    {
        return AjaxResult.success(authRequests);
    }

    @PutMapping("/admin/auth-requests/{id}")
    public AjaxResult handleAuthRequest(@PathVariable String id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> request = findById(authRequests, id);
        if (request == null)
        {
            return AjaxResult.error("授权申请不存在");
        }
        String status = str(body.get("status"));
        request.put("status", status.length() == 0 ? "approved" : status);
        request.put("handledAt", now());
        if ("approved".equals(request.get("status")))
        {
            enrollments.add(map(
                "id", "enr-" + System.currentTimeMillis(),
                "userId", request.get("userId"),
                "courseId", request.get("courseId"),
                "expiry", "2026-12-31",
                "status", "active",
                "source", "后台授权"
            ));
        }
        logOperation("授权开通", request.get("userName"), request.get("courseId"), "处理授权申请", request.get("status"));
        persistData();
        return AjaxResult.success(request);
    }

    @GetMapping("/admin/orders")
    public AjaxResult adminOrders()
    {
        refreshExpiredEnrollments();
        return AjaxResult.success(orders);
    }

    @PostMapping("/admin/orders")
    public AjaxResult adminCreateOrder(@RequestBody Map<String, Object> body)
    {
        Map<String, Object> user = findById(users, str(body.get("userId")));
        if (user == null)
        {
            return AjaxResult.error("用户不存在");
        }
        String courseId = str(body.get("courseId")).trim();
        if (courseId.length() == 0 || findCourse(courseId) == null)
        {
            return AjaxResult.error("课程不存在，请先选择有效课程");
        }
        String expiry = expiryByType(str(body.get("cardType")).length() == 0 ? "year" : str(body.get("cardType")));
        Map<String, Object> order = createOrderRecord(courseId, user, "后台开课", str(body.get("cardCode")), expiry, str(body.get("cardType")));
        order.put("studentName", str(body.get("studentName")));
        order.put("gender", str(body.get("gender")));
        order.put("recentExamScore", str(body.get("recentExamScore")));
        order.put("grade", str(body.get("grade")));
        order.put("schoolName", str(body.get("schoolName")));
        order.put("region", str(body.get("region")));
        order.put("expiresAt", expiry);
        updateEnrollmentFromOrder(order);
        logOperation("授权开通", user.get("name"), order.get("courseTitle"), "后台手动开课", "已开通");
        persistData();
        return AjaxResult.success(order);
    }

    @PutMapping("/admin/orders/{id}/close")
    public AjaxResult closeOrder(@PathVariable String id)
    {
        Map<String, Object> order = findById(orders, id);
        if (order == null)
        {
            return AjaxResult.error("开通记录不存在");
        }
        order.put("status", "closed");
        order.put("closedAt", now());
        closeEnrollmentForOrder(order);
        logOperation("授权开通", order.get("userName"), order.get("courseTitle"), "关闭课程权限", "已关闭");
        persistData();
        return AjaxResult.success(order);
    }

    @GetMapping("/admin/study")
    public AjaxResult adminStudy()
    {
        return AjaxResult.success(map(
            "attempts", attempts,
            "wrongQuestions", wrongQuestions,
            "lessonProgress", new ArrayList<>(lessonProgress.values()),
            "lessonRatings", lessonRatings,
            "aiChats", aiChats,
            "operationLogs", operationLogs,
            "feedbacks", feedbacks,
            "reinforcePoints", reinforcePoints,
            "studyPlans", studyPlans
        ));
    }

    private File dataFile()
    {
        String basePath = str(profilePath).trim();
        if (basePath.length() == 0)
        {
            basePath = System.getProperty("user.dir");
        }
        return new File(basePath, DATA_FILE_NAME);
    }

    private void persistData()
    {
        synchronized (storeLock)
        {
            try
            {
                File file = dataFile();
                File parent = file.getParentFile();
                if (parent != null && !parent.exists())
                {
                    parent.mkdirs();
                }
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshotData());
            }
            catch (IOException e)
            {
                throw new IllegalStateException("保存课程数据失败", e);
            }
        }
    }

    private static void logOperation(String type, Object user, Object course, Object detail, Object status)
    {
        Map<String, Object> log = map(
            "id", "op-" + System.currentTimeMillis(),
            "type", type,
            "user", str(user).length() == 0 ? "后台管理员" : user,
            "course", course,
            "detail", detail,
            "status", status,
            "time", now()
        );
        operationLogs.add(log);
        while (operationLogs.size() > 1000)
        {
            operationLogs.remove(0);
        }
    }

    private static Map<String, Object> snapshotData()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", copyList(users));
        data.put("courses", copyList(courses));
        data.put("enrollments", copyList(enrollments));
        data.put("docs", copyList(docs));
        data.put("questions", copyList(questions));
        data.put("reinforcePoints", copyList(reinforcePoints));
        data.put("studyPlans", copyList(studyPlans));
        data.put("orders", copyList(orders));
        data.put("authRequests", copyList(authRequests));
        data.put("activationCodes", copyList(activationCodes));
        data.put("favorites", copyList(favorites));
        data.put("feedbacks", copyList(feedbacks));
        data.put("studentBindings", copyList(studentBindings));
        data.put("attempts", copyList(attempts));
        data.put("wrongQuestions", copyList(wrongQuestions));
        data.put("lessonRatings", copyList(lessonRatings));
        data.put("aiChats", copyList(aiChats));
        data.put("operationLogs", copyList(operationLogs));
        data.put("frontendSettings", copyFrontendSettings());
        data.put("lessonProgress", copyProgress());
        return data;
    }

    private static List<Map<String, Object>> copyList(List<Map<String, Object>> source)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : source)
        {
            result.add(new LinkedHashMap<>(item));
        }
        return result;
    }

    private static Map<String, Map<String, Object>> copyProgress()
    {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : lessonProgress.entrySet())
        {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void restoreList(Map<String, Object> data, String key, List<Map<String, Object>> target)
    {
        Object value = data.get(key);
        if (!(value instanceof List))
        {
            return;
        }
        target.clear();
        for (Object item : (List<?>) value)
        {
            if (item instanceof Map)
            {
                target.add(new LinkedHashMap<>((Map<String, Object>) item));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreMap(Map<String, Object> data, String key, Map<String, Object> target)
    {
        Object value = data.get(key);
        if (!(value instanceof Map))
        {
            return;
        }
        target.clear();
        target.putAll(normalizeFrontendSettings(new LinkedHashMap<>((Map<String, Object>) value)));
    }

    private static boolean ensureFrontendSettings()
    {
        Map<String, Object> normalized = normalizeFrontendSettings(frontendSettings);
        if (normalized.equals(frontendSettings))
        {
            return false;
        }
        frontendSettings.clear();
        frontendSettings.putAll(normalized);
        return true;
    }

    private static Map<String, Object> defaultFrontendSettings()
    {
        return map(
            "homeBanners", list(defaultHomeBanner()),
            "agreements", defaultAgreements()
        );
    }

    private static Map<String, Object> defaultHomeBanner()
    {
        return map(
            "id", "banner-default",
            "title", "首页主图",
            "imageUrl", "/static/home-banner.png",
            "linkUrl", "",
            "sort", 1,
            "enabled", true,
            "remark", "默认首页轮播图"
        );
    }

    private static Map<String, Object> defaultAgreements()
    {
        return map(
            "privacy", map(
                "type", "privacy",
                "title", "隐私政策",
                "content", "请在后台配置隐私政策内容。",
                "contentFormat", "html",
                "updatedAt", now()
            ),
            "user", map(
                "type", "user",
                "title", "用户协议",
                "content", "请在后台配置用户协议内容。",
                "contentFormat", "html",
                "updatedAt", now()
            )
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeFrontendSettings(Map<String, Object> source)
    {
        Map<String, Object> settings = source == null ? new LinkedHashMap<>() : source;
        List<Map<String, Object>> banners = new ArrayList<>();
        Object rawBanners = settings.get("homeBanners");
        if (rawBanners instanceof List)
        {
            for (Object item : (List<Object>) rawBanners)
            {
                if (!(item instanceof Map))
                {
                    continue;
                }
                Map<String, Object> banner = new LinkedHashMap<>((Map<String, Object>) item);
                String imageUrl = str(banner.get("imageUrl")).trim();
                if (imageUrl.length() == 0)
                {
                    imageUrl = str(banner.get("url")).trim();
                }
                if (imageUrl.length() == 0)
                {
                    continue;
                }
                banners.add(map(
                    "id", valueOrDefault(banner.get("id"), "banner-" + System.nanoTime()),
                    "title", valueOrDefault(banner.get("title"), "首页轮播图"),
                    "imageUrl", imageUrl,
                    "linkUrl", str(banner.get("linkUrl")).trim(),
                    "sort", intValue(banner.get("sort")),
                    "enabled", boolValue(banner.get("enabled"), !"disabled".equals(str(banner.get("status")))),
                    "remark", str(banner.get("remark")).trim()
                ));
            }
        }
        if (banners.isEmpty())
        {
            banners.add(defaultHomeBanner());
        }
        banners.sort(Comparator.comparingInt(item -> intValue(item.get("sort"))));

        Map<String, Object> agreements = defaultAgreements();
        Object rawAgreements = settings.get("agreements");
        if (rawAgreements instanceof Map)
        {
            Map<String, Object> map = (Map<String, Object>) rawAgreements;
            agreements.put("privacy", normalizeAgreement("privacy", map.get("privacy"), agreements.get("privacy")));
            agreements.put("user", normalizeAgreement("user", map.get("user"), agreements.get("user")));
        }
        return map("homeBanners", banners, "agreements", agreements);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeAgreement(String type, Object value, Object fallback)
    {
        Map<String, Object> base = fallback instanceof Map ? new LinkedHashMap<>((Map<String, Object>) fallback) : new LinkedHashMap<>();
        if (value instanceof Map)
        {
            Map<String, Object> item = (Map<String, Object>) value;
            base.put("title", valueOrDefault(item.get("title"), base.get("title")));
            base.put("content", valueOrDefault(item.get("content"), base.get("content")));
            base.put("contentFormat", valueOrDefault(item.get("contentFormat"), base.get("contentFormat")));
            base.put("updatedAt", valueOrDefault(item.get("updatedAt"), now()));
        }
        base.put("type", type);
        return base;
    }

    private static Map<String, Object> copyFrontendSettings()
    {
        return normalizeFrontendSettings(frontendSettings);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> publicFrontendSettings()
    {
        Map<String, Object> copy = copyFrontendSettings();
        List<Map<String, Object>> visibleBanners = new ArrayList<>();
        for (Map<String, Object> banner : mapList(copy.get("homeBanners")))
        {
            if (boolValue(banner.get("enabled"), true))
            {
                visibleBanners.add(new LinkedHashMap<>(banner));
            }
        }
        copy.put("homeBanners", visibleBanners);
        Object rawAgreements = copy.get("agreements");
        if (rawAgreements instanceof Map)
        {
            copy.put("agreements", new LinkedHashMap<>((Map<String, Object>) rawAgreements));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void restoreProgress(Map<String, Object> data)
    {
        Object value = data.get("lessonProgress");
        if (!(value instanceof Map))
        {
            return;
        }
        lessonProgress.clear();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
        {
            if (entry.getKey() != null && entry.getValue() instanceof Map)
            {
                lessonProgress.put(String.valueOf(entry.getKey()), new LinkedHashMap<>((Map<String, Object>) entry.getValue()));
            }
        }
    }

    private static void initUsers()
    {
        users.add(map("phone", "15585827319", "password", "dyr594200", "name", "规划提升邓老师", "id", "33075", "tenantId", 52, "role", "admin", "status", "active"));
        users.add(map("phone", "13800138000", "password", "123456", "name", "张三", "id", "56596", "tenantId", 52, "role", "student", "status", "active", "grade", "高三", "region", "贵州贵阳"));
        users.add(map("phone", "13900139000", "password", "123456", "name", "李五", "id", "56597", "tenantId", 52, "role", "student", "status", "active", "grade", "高三", "region", "贵州贵阳"));
        users.add(map("phone", "18888888888", "password", "888888", "name", "王老师", "id", "10001", "tenantId", 52, "role", "teacher", "status", "active"));
        ensureBindTestStudent();
    }

    private static boolean ensureBindTestStudent()
    {
        for (Map<String, Object> user : users)
        {
            if ("19078827319".equals(user.get("phone")))
            {
                boolean changed = false;
                if (!"123456".equals(user.get("password")))
                {
                    user.put("password", "123456");
                    changed = true;
                }
                if (!"student".equals(user.get("role")))
                {
                    user.put("role", "student");
                    changed = true;
                }
                if (!"active".equals(user.get("status")))
                {
                    user.put("status", "active");
                    changed = true;
                }
                return changed;
            }
        }
        users.add(map("phone", "19078827319", "password", "123456", "name", "绑定测试学生", "id", "19078827319", "tenantId", 52, "role", "student", "status", "active", "grade", "高三", "region", "贵州贵阳"));
        return true;
    }

    private static void initCourses()
    {
        courses.add(simpleCourse("zk-yuwen-trial", "zhongkao", "trial", "中考语文", "/static/courses/zk-yuwen.jpg", 1086, 1));
        courses.add(simpleCourse("zk-shuxue-trial", "zhongkao", "trial", "中考数学", "/static/courses/zk-shuxue.jpg", 1456, 2));
        courses.add(simpleCourse("zk-yingyu-trial", "zhongkao", "trial", "中考英语", "/static/courses/zk-yingyu.jpg", 1289, 3));
        courses.add(simpleCourse("zk-wuli-trial", "zhongkao", "trial", "中考物理", "/static/courses/zk-wuli.jpg", 1176, 4));
        courses.add(simpleCourse("zk-huaxue-trial", "zhongkao", "trial", "中考化学", "/static/courses/zk-huaxue.jpg", 1237, 5));
        courses.add(simpleCourse("gk-yuwen-trial", "gaokao", "trial", "高考语文", "/static/courses/gk-yuwen.jpg", 1078, 6));
        courses.add(mathTrial());
        courses.add(simpleCourse("gk-yingyu-trial", "gaokao", "trial", "高考英语", "/static/courses/gk-yingyu.jpg", 1360, 8));
        courses.add(simpleCourse("gk-wuli-trial", "gaokao", "trial", "高考物理", "/static/courses/gk-wuli.jpg", 1121, 9));
        courses.add(simpleCourse("gk-huaxue-trial", "gaokao", "trial", "高考化学", "/static/courses/gk-huaxue.jpg", 980, 10));
        courses.add(simpleCourse("zk-yuwen-full", "zhongkao", "full", "中考语文", "/static/courses/zk-yuwen.jpg", 438, 11));
        courses.add(simpleCourse("zk-shuxue-full", "zhongkao", "full", "中考数学", "/static/courses/zk-shuxue.jpg", 521, 12));
        courses.add(simpleCourse("zk-yingyu-full", "zhongkao", "full", "中考英语", "/static/courses/zk-yingyu.jpg", 487, 13));
        courses.add(simpleCourse("zk-wuli-full", "zhongkao", "full", "中考物理", "/static/courses/zk-wuli.jpg", 366, 14));
        courses.add(simpleCourse("zk-huaxue-full", "zhongkao", "full", "中考化学", "/static/courses/zk-huaxue.jpg", 342, 15));
        courses.add(simpleCourse("gk-yuwen-full", "gaokao", "full", "高考语文", "/static/courses/gk-yuwen.jpg", 406, 16));
        courses.add(mathFull());
        courses.add(simpleCourse("gk-yingyu-full", "gaokao", "full", "高考英语", "/static/courses/gk-yingyu-full.jpg", 512, 18));
        courses.add(simpleCourse("gk-wuli-full", "gaokao", "full", "高考物理", "/static/courses/gk-wuli-full.jpg", 389, 19));
        courses.add(simpleCourse("gk-huaxue-full", "gaokao", "full", "高考化学", "/static/courses/gk-huaxue.jpg", 318, 20));
        ensureGaokaoSupplementCourses();

        enrollments.add(map("id", "enr-1", "userId", "56596", "courseId", "zk-yingyu-full", "expiry", "2027-02-15", "status", "active", "source", "授权", "studentName", "张三", "grade", "高三", "region", "贵州贵阳"));
        enrollments.add(map("id", "enr-2", "userId", "56596", "courseId", "zk-shuxue-full", "expiry", "2027-02-14", "status", "active", "source", "授权", "studentName", "张三", "grade", "高三", "region", "贵州贵阳"));
        enrollments.add(map("id", "enr-3", "userId", "56596", "courseId", "gk-math-full", "expiry", "2027-05-07", "status", "active", "source", "授权", "studentName", "张三", "grade", "高三", "region", "贵州贵阳"));
        enrollments.add(map("id", "enr-4", "userId", "56596", "courseId", "gk-yingyu-full", "expiry", "2027-01-27", "status", "active", "source", "授权", "studentName", "张三", "grade", "高三", "region", "贵州贵阳"));
        enrollments.add(map("id", "enr-5", "userId", "56596", "courseId", "gk-wuli-full", "expiry", "2027-02-05", "status", "active", "source", "授权", "studentName", "张三", "grade", "高三", "region", "贵州贵阳"));
        enrollments.add(map("id", "enr-6", "userId", "56597", "courseId", "gk-math-full", "expiry", "2027-05-07", "status", "active", "source", "授权", "studentName", "李五", "grade", "高三", "region", "贵州贵阳"));
        enrollments.add(map("id", "enr-7", "userId", "56597", "courseId", "gk-wuli-full", "expiry", "2027-02-05", "status", "active", "source", "授权", "studentName", "李五", "grade", "高三", "region", "贵州贵阳"));
    }

    private static boolean ensureGaokaoSupplementCourses()
    {
        boolean changed = false;
        changed |= addCourseIfMissing(simpleCourse("gk-shengwu-full", "gaokao", "full", "高考生物", "/static/courses/gk-huaxue.jpg", 296, 21));
        changed |= addCourseIfMissing(simpleCourse("gk-lishi-full", "gaokao", "full", "高考历史", "/static/courses/gk-dili-full.jpg", 284, 22));
        changed |= addCourseIfMissing(simpleCourse("gk-zhengzhi-full", "gaokao", "full", "高考政治", "/static/courses/gk-dili-full.jpg", 271, 23));
        changed |= addCourseIfMissing(simpleCourse("gk-dili-full", "gaokao", "full", "高考地理", "/static/courses/gk-dili-full.jpg", 302, 24));
        return changed;
    }

    private static boolean ensureGaokaoReinforcePoints()
    {
        String[] titles = {
            "根据实际问题选择函数类型", "指数函数图象特征与底数的关系", "指数式与对数式的互化", "分段函数的应用",
            "复数乘除的模", "复数加减的模", "集合与不等式", "平面向量坐标运算", "函数与导数单调性",
            "极值点效应", "数列通项公式", "数列求和问题", "三角函数合一变形", "立体几何线面关系",
            "圆锥曲线焦点弦", "概率与统计分布列", "导数压轴题构造"
        };
        String[] pointIds = {
            "kp-logic", "kp-derivative", "kp-series", "kp-gk-math-4", "kp-gk-math-5", "kp-gk-math-6",
            "kp-gk-math-7", "kp-gk-math-8", "kp-gk-math-9", "kp-gk-math-10", "kp-gk-math-11",
            "kp-gk-math-12", "kp-gk-math-13", "kp-gk-math-14", "kp-gk-math-15", "kp-gk-math-16", "kp-gk-math-17"
        };
        String[] questionIds = {"q-logic-1", "q-logic-2", "q-derivative-1", "q-series-1", "q-blank-1", "q-blank-2", "q-subjective-1"};
        boolean changed = false;
        for (int i = 0; i < titles.length; i++)
        {
            String id = pointIds[i];
            Map<String, Object> existing = findById(reinforcePoints, id);
            if (existing != null)
            {
                if (!titles[i].equals(existing.get("title")))
                {
                    existing.put("title", titles[i]);
                    changed = true;
                }
                if (!"gk-math-full".equals(existing.get("courseId")))
                {
                    existing.put("courseId", "gk-math-full");
                    changed = true;
                }
                if (str(existing.get("createdAt")).length() == 0)
                {
                    existing.put("createdAt", "2026-01-25T19:57:51");
                    changed = true;
                }
                continue;
            }
            reinforcePoints.add(map(
                "id", id,
                "courseId", "gk-math-full",
                "title", titles[i],
                "mastery", Math.max(12, 76 - i * 3),
                "status", "未学",
                "testCount", 0,
                "createdAt", "2026-01-25T19:57:51",
                "questionIds", Arrays.asList(questionIds[i % questionIds.length])
            ));
            changed = true;
        }
        return changed;
    }

    private static boolean ensureCoreTrialCourses()
    {
        boolean changed = false;
        changed |= addCourseIfMissing(simpleCourse("zk-yuwen-trial", "zhongkao", "trial", "中考语文", "/static/courses/zk-yuwen.jpg", 1086, 1));
        changed |= addCourseIfMissing(simpleCourse("zk-shuxue-trial", "zhongkao", "trial", "中考数学", "/static/courses/zk-shuxue.jpg", 1456, 2));
        changed |= addCourseIfMissing(simpleCourse("zk-yingyu-trial", "zhongkao", "trial", "中考英语", "/static/courses/zk-yingyu.jpg", 1289, 3));
        changed |= addCourseIfMissing(simpleCourse("zk-wuli-trial", "zhongkao", "trial", "中考物理", "/static/courses/zk-wuli.jpg", 1176, 4));
        changed |= addCourseIfMissing(simpleCourse("zk-huaxue-trial", "zhongkao", "trial", "中考化学", "/static/courses/zk-huaxue.jpg", 1237, 5));
        changed |= addCourseIfMissing(simpleCourse("gk-yuwen-trial", "gaokao", "trial", "高考语文", "/static/courses/gk-yuwen.jpg", 1078, 6));
        changed |= addCourseIfMissing(mathTrial());
        changed |= addCourseIfMissing(simpleCourse("gk-yingyu-trial", "gaokao", "trial", "高考英语", "/static/courses/gk-yingyu.jpg", 1360, 8));
        changed |= addCourseIfMissing(simpleCourse("gk-wuli-trial", "gaokao", "trial", "高考物理", "/static/courses/gk-wuli.jpg", 1121, 9));
        changed |= addCourseIfMissing(simpleCourse("gk-huaxue-trial", "gaokao", "trial", "高考化学", "/static/courses/gk-huaxue.jpg", 980, 10));
        return changed;
    }

    private static boolean removeUnsupportedTrialCourses()
    {
        return courses.removeIf(course -> !isAllowedTrialCourse(course));
    }

    private static boolean isAllowedTrialCourse(Map<String, Object> course)
    {
        if (!"trial".equals(course.get("kind")))
        {
            return true;
        }
        String subject = str(course.get("subject")).trim();
        if (subject.length() == 0)
        {
            subject = str(course.get("id")).trim();
        }
        subject = subject.replaceAll("^(zk|gk)-", "").replaceAll("-(trial|full)$", "");
        return ALLOWED_TRIAL_SUBJECTS.contains(subject);
    }

    private static boolean addCourseIfMissing(Map<String, Object> course)
    {
        if (findCourse(str(course.get("id"))) != null)
        {
            return false;
        }
        courses.add(course);
        return true;
    }

    private static void initDocs()
    {
        ensureCourseDocDefaults();
    }

    private static boolean ensureCourseDocDefaults()
    {
        boolean changed = false;
        changed |= addDocIfMissing(map("id", "doc-1", "courseId", "gk-math-full", "category", "lecture", "title", "高考数学集合逻辑讲义.pdf", "fileUrl", "#", "fileType", "PDF", "size", "1.2MB", "uploadTime", "2026-05-26T10:11:00", "visible", true));
        changed |= addDocIfMissing(map("id", "doc-2", "courseId", "gk-math-full", "category", "lecture", "title", "导数极值专题学案.pdf", "fileUrl", "#", "fileType", "PDF", "size", "2.4MB", "uploadTime", "2026-05-26T10:11:00", "visible", true));
        changed |= addDocIfMissing(map("id", "doc-3", "courseId", "zk-yingyu-full", "category", "lecture", "title", "中考英语核心词汇表.xlsx", "fileUrl", "#", "fileType", "XLSX", "size", "640KB", "uploadTime", "2026-05-26T10:11:00", "visible", true));
        changed |= addDocIfMissing(map("id", "paper-1", "courseId", "gk-math-full", "category", "paper", "title", "高考数学集合逻辑测试卷.pdf", "fileUrl", "#", "fileType", "PDF", "size", "1.2MB", "uploadTime", "2026-05-26T10:15:00", "visible", true));
        changed |= addDocIfMissing(map("id", "paper-2", "courseId", "gk-math-full", "category", "paper", "title", "导数极值专题测试卷.pdf", "fileUrl", "#", "fileType", "PDF", "size", "2.4MB", "uploadTime", "2026-05-26T10:15:00", "visible", true));
        changed |= addDocIfMissing(map("id", "paper-3", "courseId", "zk-yingyu-full", "category", "paper", "title", "中考英语核心词汇测试卷.xlsx", "fileUrl", "#", "fileType", "XLSX", "size", "640KB", "uploadTime", "2026-05-26T10:15:00", "visible", true));
        for (Map<String, Object> doc : docs)
        {
            changed |= ensureDocDefaults(doc);
        }
        return changed;
    }

    private static boolean ensureDocDefaults(Map<String, Object> doc)
    {
        boolean changed = false;
        String courseId = scopedCourseId(doc.get("courseId"));
        if (!courseId.equals(str(doc.get("courseId"))))
        {
            doc.put("courseId", courseId);
            changed = true;
        }
        if (str(doc.get("courseTitle")).length() == 0 || !str(doc.get("courseTitle")).equals(resolveCourseTitle(courseId)))
        {
            doc.put("courseTitle", resolveCourseTitle(courseId));
            changed = true;
        }
        if (str(doc.get("category")).length() == 0)
        {
            doc.put("category", str(doc.get("title")).contains("试卷") ? "paper" : "lecture");
            changed = true;
        }
        if (str(doc.get("uploadTime")).length() == 0)
        {
            doc.put("uploadTime", "paper".equals(doc.get("category")) ? "2026-05-26T10:15:00" : "2026-05-26T10:11:00");
            changed = true;
        }
        if (!doc.containsKey("visible"))
        {
            doc.put("visible", true);
            changed = true;
        }
        return changed;
    }

    private static boolean addDocIfMissing(Map<String, Object> doc)
    {
        if (findById(docs, str(doc.get("id"))) != null)
        {
            return false;
        }
        docs.add(doc);
        return true;
    }

    private static void initQuestions()
    {
        questions.add(map("id", "q-logic-1", "questionType", "choice", "stem", "已知集合 A={x|x>1}，B={x|x<4}，则 A∩B 为", "options", Arrays.asList("x>1", "x<4", "1<x<4", "空集"), "answer", 2, "analysis", "交集要同时满足两个条件，所以是 1<x<4。", "knowledge", "集合交集"));
        questions.add(map("id", "q-logic-2", "questionType", "choice", "stem", "命题“若 p 则 q”的逆否命题是", "options", Arrays.asList("若 q 则 p", "若非 p 则非 q", "若非 q 则非 p", "p 且 q"), "answer", 2, "analysis", "原命题与逆否命题等价，逆否为若非 q 则非 p。", "knowledge", "充分必要条件"));
        questions.add(map("id", "q-derivative-1", "questionType", "choice", "stem", "函数 f(x)=x^2 在 x=2 处的导数为", "options", Arrays.asList("2", "3", "4", "5"), "answer", 2, "analysis", "f'(x)=2x，代入 x=2 得 4。", "knowledge", "导数计算"));
        questions.add(map("id", "q-series-1", "questionType", "choice", "stem", "等差数列首项为 2，公差为 3，则第 5 项为", "options", Arrays.asList("11", "12", "13", "14"), "answer", 3, "analysis", "a5=a1+4d=2+12=14。", "knowledge", "等差数列"));
        questions.add(map("id", "q-blank-1", "questionType", "fill", "stem", "函数 f(x)=x^2 的导函数是 f'(x)=____。", "options", new ArrayList<Object>(), "answerText", "2x", "acceptableAnswers", Arrays.asList("2x", "2*x"), "analysis", "幂函数求导公式为 (x^n)'=nx^(n-1)，所以 x^2 的导函数是 2x。", "knowledge", "导数计算"));
        questions.add(map("id", "q-blank-2", "questionType", "fill", "stem", "等差数列通项公式可写为 an=a1+____d。", "options", new ArrayList<Object>(), "answerText", "n-1", "acceptableAnswers", Arrays.asList("n-1", "(n-1)"), "analysis", "等差数列第 n 项比首项多 n-1 个公差。", "knowledge", "等差数列"));
        questions.add(map("id", "q-subjective-1", "questionType", "subjective", "stem", "简述用导数判断函数单调性的基本步骤。", "options", new ArrayList<Object>(), "answerText", "先求定义域，再求导函数，解 f'(x)>0 和 f'(x)<0 的区间，最后写出对应的增减区间。", "analysis", "主观题重点看步骤完整性：定义域、求导、判符号、写区间。", "knowledge", "导数应用"));
    }

    private static void initActivationCodes()
    {
        activationCodes.add(activationCode("GK-MATH-2026", "gk-math-full", "year", "33075"));
        activationCodes.add(activationCode("GK-MATH-72H", "gk-math-full", "hours72", "33075"));
        activationCodes.add(activationCode("GK-MATH-7D", "gk-math-full", "days7", "33075"));
        activationCodes.add(activationCode("ZK-ENG-2026", "zk-yingyu-full", "year", "33075"));
        activationCodes.add(activationCode("ZK-ENG-72H", "zk-yingyu-full", "hours72", "33075"));
        activationCodes.add(activationCode("ZK-ENG-7D", "zk-yingyu-full", "days7", "33075"));
    }

    private static void initStudyData()
    {
        studentBindings.add(map("id", "stu-bind-demo-1", "ownerUserId", "33075", "studentUserId", "56596", "createdAt", now()));
        studentBindings.add(map("id", "stu-bind-demo-2", "ownerUserId", "33075", "studentUserId", "56597", "createdAt", now()));
        reinforcePoints.add(map("id", "kp-logic", "courseId", "gk-math-full", "title", "集合与逻辑", "mastery", 68, "status", "待复习", "testCount", 3, "questionIds", Arrays.asList("q-logic-1", "q-logic-2")));
        reinforcePoints.add(map("id", "kp-derivative", "courseId", "gk-math-full", "title", "导数基础", "mastery", 74, "status", "复习中", "testCount", 5, "questionIds", Arrays.asList("q-derivative-1")));
        reinforcePoints.add(map("id", "kp-series", "courseId", "gk-math-full", "title", "数列通项", "mastery", 58, "status", "薄弱", "testCount", 2, "questionIds", Arrays.asList("q-series-1")));
        ensureGaokaoReinforcePoints();
        studyPlans.add(map("courseId", "gk-math-full", "title", "高考数学阶段学案", "tasks", list(
            map("id", "plan-1", "title", "复习集合交集与补集", "type", "知识回顾", "done", true),
            map("id", "plan-2", "title", "完成导数基础 3 道巩固题", "type", "训练任务", "done", false),
            map("id", "plan-3", "title", "整理错题与巩固中的数列题", "type", "错题复盘", "done", false)
        )));
    }

    private static void initFrontendSettings()
    {
        frontendSettings.clear();
        frontendSettings.putAll(defaultFrontendSettings());
    }

    private static Map<String, Object> mathTrial()
    {
        Map<String, Object> course = simpleCourse("gk-math-trial", "gaokao", "trial", "高考数学", "/static/courses/gk-shuxue.jpg", 1450, 7);
        course.put("subject", "gaokao-math");
        course.put("title", "高考数学");
        course.put("courseName", "《高考数学》试听课");
        course.put("introduction", "《高考数学》试听课");
        course.put("detailCover", "/static/courses/gk-shuxue-trial-detail.jpg");
        course.put("updatedAt", "2026-05-26T10:11:00");
        course.put("totalLessons", 6);
        course.put("totalDuration", "02小时13分");
        course.put("practiceDuration", "01小时11分");
        course.put("readDuration", "00小时41分");
        course.put("chapters", list(
            trialChapter("1.集合逻辑不等式", 7),
            trialChapter("2.奇偶性与单调性", 7),
            trialChapter("3.周期性与对称性", 6),
            trialChapter("4.导数——单调性与极值、最值", 6),
            trialChapter("5.通项公式前n项和", 6),
            trialChapter("6.定值与定点问题", 2)
        ));
        course.put("quizzes", list(makeQuiz("入门测", "未学习", "去测评"), makeQuiz("第六章 数列", "学习中：1/14", "去测评")));
        return course;
    }

    private static Map<String, Object> mathFull()
    {
        Map<String, Object> course = simpleCourse("gk-math-full", "gaokao", "full", "高考数学", "/static/courses/gk-shuxue-full.jpg", 117, 17);
        course.put("subject", "gaokao-math");
        course.put("title", "高考数学");
        course.put("courseName", "《高考数学》");
        course.put("introduction", "《高考数学》");
        course.put("detailCover", "/static/courses/gk-shuxue-full-detail.jpg");
        course.put("updatedAt", "2026-05-26T10:15:00");
        course.put("totalLessons", 76);
        course.put("totalDuration", "04小时53分");
        course.put("practiceDuration", "22小时00分");
        course.put("readStudyCount", 1);
        course.put("readDuration", "00小时21分");
        course.put("progress", 1);
        course.put("versions", list(
            map("name", "2026版", "chapters", list(
                chapter("一、复数", "1.复数乘除的模（三角法）", "2.复数加减的模（图解法）", "3.速算复数的模运算", "4.零点权重思维"),
                chapter("二、集合与不等式", "5.集合与不等式（答案反代法）", "6.权方和不等式的应用", "7.万能K法解不等式", "8.对称法解不等式", "9.最大值、最小值与平凡恒等式", "10.充要条件的判断"),
                chapter("三、平面向量", "11.平面向量三板斧（坐标法）", "12.平面向量三板斧（图解法）", "13.平面向量三板斧（公式法）"),
                chapter("四、函数与导数", "16.抽象函数（构造法）", "17.抽象函数（赋值法）", "18.抽象函数广义奇偶性（图解法）", "23.极值点效应（保号性定理）", "29.导数邂逅三角函数"),
                chapter("九、数列", "60.等差数列的计算", "61.等差数列的函数属性", "65.数列求通项问题", "66.数列求和问题")
            )),
            map("name", "绝招课", "chapters", list(
                chapter("一、基础版", "1.集合逻辑不等式", "2.函数的基本概念与表示", "4.奇偶性与单调性", "8.导数——单调性与极值、最值", "12.通项公式与前n项和"),
                chapter("二、进阶版", "32.导函数构造不等式", "33.端点效应解题大招", "41.不等式速刷", "45.数列不动点求通项大招")
            ))
        ));
        course.put("quizzes", list(
            makeQuiz("入门测", "学习中：0/20", "去测评"),
            makeQuiz("第一章 集合、常用逻辑用语、不等式", "学习中：0/15", "去测评"),
            makeQuiz("第二章 函数的概念及其表示", "已完成", "测评报告"),
            makeQuiz("第三章 导数", "已完成", "测评报告"),
            makeQuiz("第六章 数列", "未学习", "去测评")
        ));
        return course;
    }

    private static Map<String, Object> simpleCourse(String id, String stage, String kind, String full, String cover, int studyCount, int sort)
    {
        String displayFull = stripCourseYear(full);
        List<Map<String, Object>> trialChapters = new ArrayList<>();
        if ("trial".equals(kind))
        {
            trialChapters = list(
                trialChapter(displayFull + "导学试听", 5),
                trialChapter(displayFull + "核心技巧试听", 5),
                trialChapter(displayFull + "真题讲练试听", 5)
            );
        }
        return map(
            "id", id,
            "stage", stage,
            "kind", kind,
            "subject", id.replaceAll("-(trial|full)$", ""),
            "full", displayFull,
            "title", displayFull,
            "courseName", "trial".equals(kind) ? "《" + displayFull + "》试听课" : "《" + displayFull + "》",
            "introduction", "trial".equals(kind) ? "《" + displayFull + "》试听课" : "《" + displayFull + "》",
            "cover", cover,
            "detailCover", cover,
            "updatedAt", "trial".equals(kind) ? "2026-05-26T10:11:00" : "2026-05-26T10:15:00",
            "openMode", "trial".equals(kind) ? "trial" : "card",
            "openText", "trial".equals(kind) ? "试听免费" : "激活课程",
            "studyCount", studyCount,
            "totalLessons", "trial".equals(kind) ? 3 : 105,
            "totalDuration", "trial".equals(kind) ? "01小时29分" : "20小时37分",
            "practiceDuration", "trial".equals(kind) ? "" : "02小时23分",
            "readStudyCount", 0,
            "readDuration", "00小时00分",
            "progress", 0,
            "sort", sort,
            "status", "published",
            "versions", list(map("name", "2026版", "chapters", trialChapters)),
            "chapters", trialChapters,
            "quizzes", list(makeQuiz("入门测", "未学习", "去测评"))
        );
    }

    private static Map<String, Object> trialChapter(String title, int practiceTotal)
    {
        return map("title", title, "open", true, "audition", true, "children", list(
            map("name", "技巧干货", "type", 1, "total", 1, "read", 0),
            map("name", "真题讲练", "type", 2, "total", practiceTotal, "read", 0)
        ));
    }

    private static Map<String, Object> chapter(String title, String... lessonNames)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < lessonNames.length; i++)
        {
            items.add(map("title", lessonNames[i], "open", false, "children", list(
                map("name", "技巧干货", "type", 1, "total", 1, "read", i == 0 ? 1 : 0),
                map("name", "真题讲练", "type", 2, "total", i + 2, "read", 0)
            )));
        }
        return map("title", title, "open", false, "items", items);
    }

    private static Map<String, Object> makeQuiz(String name, String status, String action)
    {
        return map("name", name, "status", status, "action", action);
    }

    private static List<Map<String, Object>> filteredCourses(Map<String, String> params)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        String tab = params.get("tab");
        String stage = params.get("stage");
        String kind = params.get("kind");
        String status = params.get("status");
        for (Map<String, Object> course : courses)
        {
            if (!isAllowedTrialCourse(course))
            {
                continue;
            }
            if (tab != null && tab.length() > 0)
            {
                int index = Integer.parseInt(tab);
                String targetStage = index < 2 ? "zhongkao" : "gaokao";
                String targetKind = index == 0 || index == 2 ? "trial" : "full";
                if (!targetStage.equals(course.get("stage")) || !targetKind.equals(course.get("kind")))
                {
                    continue;
                }
            }
            if (stage != null && stage.length() > 0 && !stage.equals(course.get("stage")))
            {
                continue;
            }
            if (kind != null && kind.length() > 0 && !kind.equals(course.get("kind")))
            {
                continue;
            }
            if (status != null && status.length() > 0 && !status.equals(course.get("status")))
            {
                continue;
            }
            list.add(course);
        }
        list.sort(Comparator.comparingInt(item -> intValue(item.get("sort"))));
        return list;
    }

    private static Map<String, Object> courseListItem(Map<String, Object> course)
    {
        return map(
            "id", course.get("id"),
            "stage", course.get("stage"),
            "kind", course.get("kind"),
            "subject", course.get("subject"),
            "full", stripCourseYear(course.get("full")),
            "title", stripCourseYear(course.get("title")),
            "suffix", "trial".equals(course.get("kind")) ? "试听课" : "",
            "sub", stripCourseYear(course.get("courseName")),
            "openMode", course.get("openMode"),
            "openText", course.get("openText"),
            "cover", course.get("cover"),
            "isTry", "trial".equals(course.get("kind")),
            "studyCount", course.get("studyCount"),
            "status", course.get("status"),
            "sort", course.get("sort")
        );
    }

    private static Map<String, Object> courseForApp(Map<String, Object> course, Map<String, Object> user)
    {
        Map<String, Object> result = objectMapper.convertValue(course, new TypeReference<Map<String, Object>>() {});
        result.put("full", stripCourseYear(result.get("full")));
        result.put("title", stripCourseYear(result.get("title")));
        result.put("courseName", stripCourseYear(result.get("courseName")));
        result.put("introduction", stripCourseYear(result.get("introduction")));
        result.put("available", hasActiveEnrollment(user, scopedCourseId(result.get("id"))));
        applyComputedCourseStats(result, user);
        applyAttemptStatusToCourse(result, user);
        return result;
    }

    private static void applyAttemptStatusToCourse(Map<String, Object> course, Map<String, Object> user)
    {
        if (user == null)
        {
            return;
        }
        String courseId = scopedCourseId(course.get("id"));
        applyQuizAttemptStatus(mapList(course.get("quizzes")), user, courseId, "quiz");
        for (Map<String, Object> version : mapList(course.get("versions")))
        {
            for (Map<String, Object> chapter : mapList(version.get("chapters")))
            {
                for (Map<String, Object> lesson : mapList(chapter.get("items")))
                {
                    for (Map<String, Object> child : mapList(lesson.get("children")))
                    {
                        if (intValue(child.get("type")) == 2)
                        {
                            int count = attemptCount(user, courseId, str(child.get("name")), "");
                            if (count > 0)
                            {
                                child.put("status", "已完成" + count + "次");
                                child.put("read", count);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void applyQuizAttemptStatus(List<Map<String, Object>> quizzes, Map<String, Object> user, String courseId, String type)
    {
        for (Map<String, Object> quiz : quizzes)
        {
            int count = attemptCount(user, courseId, str(quiz.get("name")), type);
            if (count > 0)
            {
                quiz.put("status", "已完成" + count + "次");
            }
        }
    }

    private static int attemptCount(Map<String, Object> user, String courseId, String title, String type)
    {
        int count = 0;
        for (Map<String, Object> attempt : attempts)
        {
            if (!sameUser(attempt, user))
            {
                continue;
            }
            if (courseId.length() > 0 && !courseId.equals(scopedCourseId(attempt.get("courseId"))))
            {
                continue;
            }
            if (type.length() > 0 && !type.equals(str(attempt.get("type"))))
            {
                continue;
            }
            if (title.length() == 0 || title.equals(str(attempt.get("title"))) || str(attempt.get("title")).contains(title))
            {
                count++;
            }
        }
        return count;
    }

    private static void applyComputedCourseStats(Map<String, Object> course, Map<String, Object> user)
    {
        int lessonCount = countUploadedCourseLessons(course);
        if (lessonCount > 0)
        {
            course.put("totalLessons", lessonCount);
        }
        int durationSeconds = sumUploadedDurationSeconds(course);
        if (durationSeconds > 0)
        {
            course.put("totalDuration", secondsText(durationSeconds));
        }
        if (user != null)
        {
            Map<String, Object> progress = courseProgressStats(user, str(course.get("id")), intValue(course.get("totalLessons")));
            course.put("readStudyCount", progress.get("readStudyCount"));
            course.put("readDuration", progress.get("readDuration"));
            course.put("progress", progress.get("progress"));
        }
    }

    private static int countUploadedCourseLessons(Map<String, Object> course)
    {
        List<Map<String, Object>> versions = mapList(course.get("versions"));
        List<Map<String, Object>> chapters = new ArrayList<>();
        if (!versions.isEmpty())
        {
            chapters = mapList(versions.get(0).get("chapters"));
        }
        if (chapters.isEmpty())
        {
            chapters = mapList(course.get("chapters"));
        }
        int total = 0;
        for (Map<String, Object> chapter : chapters)
        {
            total += countLessonItems(mapList(chapter.get("items")));
            total += countLessonItems(mapList(chapter.get("children")));
        }
        return total;
    }

    private static int sumUploadedDurationSeconds(Map<String, Object> course)
    {
        List<Map<String, Object>> versions = mapList(course.get("versions"));
        List<Map<String, Object>> chapters = new ArrayList<>();
        if (!versions.isEmpty())
        {
            chapters = mapList(versions.get(0).get("chapters"));
        }
        if (chapters.isEmpty())
        {
            chapters = mapList(course.get("chapters"));
        }
        int total = 0;
        for (Map<String, Object> chapter : chapters)
        {
            total += sumDurationInItems(mapList(chapter.get("items")));
            total += sumDurationInItems(mapList(chapter.get("children")));
        }
        return total;
    }

    private static int sumDurationInItems(List<Map<String, Object>> items)
    {
        int total = 0;
        for (Map<String, Object> item : items)
        {
            List<Map<String, Object>> children = mapList(item.get("children"));
            if (!children.isEmpty())
            {
                total += sumDurationInItems(children);
            }
            else if (intValue(item.get("type")) != 2)
            {
                total += durationSeconds(item);
            }
        }
        return total;
    }

    private static int durationSeconds(Map<String, Object> item)
    {
        int seconds = intValue(item.get("durationSeconds"));
        if (seconds > 0)
        {
            return seconds;
        }
        seconds = intValue(item.get("duration"));
        if (seconds > 0)
        {
            return seconds;
        }
        int minutes = intValue(item.get("durationMinutes"));
        return minutes > 0 ? minutes * 60 : 0;
    }

    private static int countLessonItems(List<Map<String, Object>> items)
    {
        int total = 0;
        for (Map<String, Object> item : items)
        {
            List<Map<String, Object>> children = mapList(item.get("children"));
            if (!children.isEmpty())
            {
                for (Map<String, Object> child : children)
                {
                    if (intValue(child.get("type")) != 2)
                    {
                        total++;
                    }
                }
            }
            else if (intValue(item.get("type")) != 2)
            {
                total++;
            }
        }
        return total;
    }

    private static Map<String, Object> courseProgressStats(Map<String, Object> user, String courseId, int totalLessons)
    {
        int learned = 0;
        int seconds = 0;
        for (Map<String, Object> progress : lessonProgress.values())
        {
            if (!sameUser(progress, user) || !courseId.equals(str(progress.get("courseId"))))
            {
                continue;
            }
            seconds += (int) Math.round(doubleValue(progress.get("currentTime")));
            if (Boolean.TRUE.equals(progress.get("ended")) || intValue(progress.get("percent")) >= 90)
            {
                learned++;
            }
        }
        int percent = totalLessons <= 0 ? 0 : Math.min(100, Math.round(learned * 100f / totalLessons));
        return map("readStudyCount", learned, "readDuration", secondsText(seconds), "progress", percent);
    }

    private static Map<String, Object> grade(Map<String, Object> payload, Map<String, Object> user)
    {
        String title = str(payload.get("title"));
        if (title.length() == 0)
        {
            title = str(payload.get("quizId"));
        }
        if (title.length() == 0)
        {
            title = "真题讲练";
        }
        String type = str(payload.get("type"));
        if (type.length() == 0)
        {
            type = "practice";
        }
        String courseId = scopedCourseId(payload.get("courseId"));
        payload.put("courseId", courseId);
        Map<String, Object> answers = mapValue(payload.get("answers"));
        Map<String, Object> answerImages = mapValue(payload.get("answerImages"));
        Map<String, Object> skipped = mapValue(payload.get("skipped"));
        Map<String, Object> noUploads = mapValue(payload.get("noUploads"));
        Map<String, Object> selfReviews = mapValue(payload.get("selfReviews"));
        List<String> questionIds = stringList(payload.get("questionIds"));
        List<String> sourceWrongIds = stringList(payload.get("sourceWrongIds"));
        List<Map<String, Object>> source = questionIds.isEmpty() ? questionsFor(title) : questionsByIds(questionIds);
        int correct = 0;
        int gradable = 0;
        int manualReviewCount = 0;
        double earned = 0d;
        List<Map<String, Object>> details = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> q : source)
        {
            String questionId = str(q.get("id"));
            Object submitted = answers.get(q.get("id"));
            String questionType = questionType(q);
            boolean skippedQuestion = boolValue(skipped.get(questionId), false);
            boolean noUpload = boolValue(noUploads.get(questionId), false);
            String selfReviewResult = selfReviews.containsKey(questionId) ? normalizeReviewResult(selfReviews.get(questionId), null) : "";
            boolean reviewedManually = selfReviewResult.length() > 0;
            boolean manualReview = isSelfReviewQuestion(q) && !skippedQuestion && !reviewedManually;
            boolean partial = "partial".equals(selfReviewResult);
            boolean ok = reviewedManually ? "correct".equals(selfReviewResult) : (!manualReview && !skippedQuestion && questionAnswerMatches(q, submitted));
            int selected = isChoiceQuestion(q) ? intValue(submitted) : -1;
            String selectedText = skippedQuestion ? "已跳过" : (isChoiceQuestion(q) ? optionText(q, selected) : firstNonBlank(str(submitted).trim(), noUpload ? "暂不上传" : ""));
            String answerText = correctAnswerText(q);
            String studentAnswerImageUrl = str(answerImages.get(questionId)).trim();
            if (manualReview)
            {
                manualReviewCount++;
            }
            else
            {
                gradable++;
            }
            String sourceWrongId = index < sourceWrongIds.size() ? sourceWrongIds.get(index) : "";
            Map<String, Object> sourceWrong = sourceWrongId.length() == 0 ? null : findById(wrongQuestions, sourceWrongId);
            if (sourceWrong != null && (!sameUser(sourceWrong, user) || !courseId.equals(scopedCourseId(sourceWrong.get("courseId")))))
            {
                sourceWrong = null;
                sourceWrongId = "";
            }
            String originType = sourceWrong == null ? str(payload.get("originType")) : str(sourceWrong.get("type"));
            if (ok)
            {
                correct++;
                earned += 1d;
            }
            else
            {
                if (partial)
                {
                    earned += 0.5d;
                }
                if (!manualReview)
                {
                    Map<String, Object> wrong = map(
                        "id", "wrong-" + System.currentTimeMillis() + "-" + index,
                        "userId", user == null ? null : user.get("id"),
                        "questionId", q.get("id"),
                        "questionType", questionType,
                        "sourceWrongId", sourceWrongId,
                        "originType", originType,
                        "courseId", payload.get("courseId"),
                        "courseTitle", resolveCourseTitle(payload.get("courseId")),
                        "subjectTitle", resolveSubjectTitle(payload.get("courseId")),
                        "title", title,
                        "type", type,
                        "sourceType", sourceLabel(type),
                        "stem", q.get("stem"),
                        "options", q.get("options"),
                        "answer", q.get("answer"),
                        "answerText", answerText,
                        "selected", selected,
                        "selectedText", selectedText,
                        "studentAnswerImageUrl", studentAnswerImageUrl,
                        "skipped", skippedQuestion,
                        "noUpload", noUpload,
                        "reviewResult", reviewedManually ? selfReviewResult : "",
                        "partialCredit", partial ? 0.5d : (ok ? 1d : 0d),
                        "analysis", q.get("analysis"),
                        "analysisImageUrl", analysisImageUrl(q),
                        "videoAnalysisUrl", analysisVideoUrl(q),
                        "knowledge", q.get("knowledge"),
                        "mastered", false,
                        "retryCount", isWrongRetryType(type) ? 1 : 0,
                        "retryWrongCount", isWrongRetryType(type) ? 1 : 0,
                        "updatedAt", now()
                    );
                    recordWrongQuestion(wrong, user);
                }
            }
            details.add(map("id", q.get("id"), "stem", q.get("stem"), "questionType", questionType, "selected", isChoiceQuestion(q) ? selected : selectedText, "answer", isChoiceQuestion(q) ? q.get("answer") : answerText, "answerText", answerText, "answerImageUrl", str(q.get("answerImageUrl")), "studentAnswerImageUrl", studentAnswerImageUrl, "correct", ok, "manualReview", manualReview, "selfReviewed", !manualReview, "reviewResult", manualReview ? "pending" : (reviewedManually ? selfReviewResult : (ok ? "correct" : "wrong")), "partialCredit", partial ? 0.5d : (ok ? 1d : 0d), "skipped", skippedQuestion, "noUpload", noUpload, "analysis", q.get("analysis"), "analysisImageUrl", analysisImageUrl(q), "videoAnalysisUrl", analysisVideoUrl(q)));
            Map<String, Object> detail = details.get(details.size() - 1);
            detail.put("sourceWrongId", sourceWrongId);
            detail.put("selectedText", selectedText);
            if (isChoiceQuestion(q))
            {
                detail.put("answerText", optionText(q, intValue(q.get("answer"))));
            }
            detail.put("videoAnalysisUrl", analysisVideoUrl(q));
            detail.put("analysisImageUrl", analysisImageUrl(q));
            index++;
        }
        updateWrongRetryStats(type, sourceWrongIds, details, user, courseId);
        Map<String, Object> attempt = map(
            "id", "attempt-" + System.currentTimeMillis(),
            "userId", user == null ? null : user.get("id"),
            "courseId", payload.get("courseId"),
            "courseTitle", resolveCourseTitle(payload.get("courseId")),
            "subjectTitle", resolveSubjectTitle(payload.get("courseId")),
            "title", title,
            "type", type,
            "sourceType", sourceLabel(type),
            "total", source.size(),
            "correct", correct,
            "manualReviewCount", manualReviewCount,
            "score", gradable <= 0 ? 0 : Math.round((float) (earned * 100d / gradable)),
            "createdAt", now(),
            "details", details
        );
        attempts.add(attempt);
        return attempt;
    }

    private static List<Map<String, Object>> questionsFor(String title)
    {
        if (title.contains("导数") || title.contains("极值"))
        {
            return questionsByIds(Arrays.asList("q-derivative-1", "q-logic-2", "q-blank-1", "q-subjective-1"));
        }
        if (title.contains("数列") || title.contains("通项"))
        {
            return questionsByIds(Arrays.asList("q-series-1", "q-logic-1", "q-blank-2", "q-subjective-1"));
        }
        if (title.contains("集合") || title.contains("逻辑") || title.contains("不等式"))
        {
            return questionsByIds(Arrays.asList("q-logic-1", "q-logic-2", "q-blank-1", "q-subjective-1"));
        }
        return questions.subList(0, Math.min(3, questions.size()));
    }

    private static Map<String, Object> findCourseQuiz(String quizId, String courseId)
    {
        String target = str(quizId).trim();
        String scopedCourseId = str(courseId).trim();
        if (target.length() == 0)
        {
            return null;
        }
        for (Map<String, Object> course : courses)
        {
            if (scopedCourseId.length() > 0 && !scopedCourseId.equals(str(course.get("id"))))
            {
                continue;
            }
            List<Map<String, Object>> quizList = new ArrayList<>();
            quizList.addAll(mapList(course.get("quizzes")));
            quizList.addAll(mapList(course.get("knowledgeQuizzes")));
            for (Map<String, Object> quiz : quizList)
            {
                if (target.equals(str(quiz.get("id"))) || target.equals(str(quiz.get("name"))))
                {
                    return quiz;
                }
            }
        }
        return null;
    }

    private static List<Map<String, Object>> questionsByIds(List<String> ids)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : ids)
        {
            Map<String, Object> question = findById(questions, id);
            if (question != null)
            {
                list.add(question);
            }
        }
        return list;
    }

    private static boolean shouldAutoDrawQuestions(String type, String title)
    {
        String normalizedType = str(type).trim();
        String normalizedTitle = str(title);
        return "quiz".equals(normalizedType)
            || "reinforce".equals(normalizedType)
            || normalizedTitle.contains("章节扫雷")
            || normalizedTitle.contains("复习测试")
            || normalizedTitle.contains("知识巩固");
    }

    private static List<Map<String, Object>> drawPaperQuestions(List<Map<String, Object>> source, String type)
    {
        if (source == null || source.isEmpty())
        {
            return source == null ? new ArrayList<>() : source;
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        drawByQuestionType(source, selected, usedIds, "choice", 6, type);
        drawByQuestionType(source, selected, usedIds, "fill", 3, type);
        drawByQuestionType(source, selected, usedIds, "subjective", 1, type);
        if (selected.size() < Math.min(10, source.size()))
        {
            drawByQuestionType(source, selected, usedIds, "", Math.min(10, source.size()) - selected.size(), type);
        }
        incrementQuestionDrawStats(selected, type);
        return selected;
    }

    private static void drawByQuestionType(List<Map<String, Object>> source, List<Map<String, Object>> selected, Set<String> usedIds, String questionType, int limit, String drawType)
    {
        if (limit <= 0)
        {
            return;
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> question : source)
        {
            String id = str(question.get("id"));
            if (id.length() == 0 || usedIds.contains(id))
            {
                continue;
            }
            if (questionType.length() == 0 || questionType.equals(questionType(question)))
            {
                candidates.add(question);
            }
        }
        Collections.shuffle(candidates);
        String countKey = drawCounterKey(drawType);
        candidates.sort((a, b) -> Integer.compare(intValue(a.get(countKey)), intValue(b.get(countKey))));
        for (Map<String, Object> question : candidates)
        {
            selected.add(question);
            usedIds.add(str(question.get("id")));
            if (selected.size() >= 10 || --limit <= 0)
            {
                return;
            }
        }
    }

    private static String drawCounterKey(String type)
    {
        String value = str(type).trim();
        if ("quiz".equals(value))
        {
            return "quizDrawCount";
        }
        if ("reinforce".equals(value))
        {
            return "reinforceDrawCount";
        }
        return "drawCount";
    }

    private static void incrementQuestionDrawStats(List<Map<String, Object>> selected, String type)
    {
        String countKey = drawCounterKey(type);
        for (Map<String, Object> question : selected)
        {
            question.put("drawCount", intValue(question.get("drawCount")) + 1);
            question.put(countKey, intValue(question.get(countKey)) + 1);
            question.put("lastDrawnAt", now());
        }
    }

    private static List<Map<String, Object>> publicQuestions(List<Map<String, Object>> source)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> q : source)
        {
            Map<String, Object> item = new LinkedHashMap<>(q);
            boolean manualReview = isSelfReviewQuestion(q);
            item.remove("answer");
            item.remove("answerText");
            item.remove("answerImageUrl");
            item.remove("acceptableAnswers");
            item.put("manualReview", manualReview);
            if (manualReview)
            {
                item.put("answerText", correctAnswerText(q));
                item.put("answerImageUrl", str(q.get("answerImageUrl")));
            }
            list.add(item);
        }
        return list;
    }

    private static void normalizeQuestion(Map<String, Object> question)
    {
        String type = questionType(question);
        question.put("questionType", type);
        if (!question.containsKey("difficulty") || intValue(question.get("difficulty")) <= 0)
        {
            question.put("difficulty", 1);
        }
        String imageUrl = analysisImageUrl(question);
        if (imageUrl.length() > 0)
        {
            question.put("analysisImageUrl", imageUrl);
        }
        String videoUrl = analysisVideoUrl(question);
        if (videoUrl.length() > 0)
        {
            question.put("videoAnalysisUrl", videoUrl);
        }
        String answerImageUrl = str(question.get("answerImageUrl")).trim();
        if (answerImageUrl.length() > 0)
        {
            question.put("answerImageUrl", answerImageUrl);
        }
        if (isChoiceQuestion(question))
        {
            if (!(question.get("options") instanceof List))
            {
                question.put("options", new ArrayList<Object>());
            }
            question.put("answer", intValue(question.get("answer")));
            return;
        }
        if (!(question.get("options") instanceof List))
        {
            question.put("options", new ArrayList<Object>());
        }
        String answerText = valueOrDefault(question.get("answerText"), question.get("answer")).trim();
        question.put("answerText", answerText);
        if ("fill".equals(type) && stringList(question.get("acceptableAnswers")).isEmpty() && answerText.length() > 0)
        {
            question.put("acceptableAnswers", answerCandidates(answerText));
        }
    }

    private static String questionType(Map<String, Object> question)
    {
        if (question == null)
        {
            return "choice";
        }
        String raw = str(question.get("questionType")).trim();
        if (raw.length() == 0)
        {
            raw = str(question.get("type")).trim();
        }
        if ("填空题".equals(raw) || "填空".equals(raw) || "blank".equals(raw) || "fill_blank".equals(raw))
        {
            return "fill";
        }
        if ("主观题".equals(raw) || "主观".equals(raw) || "essay".equals(raw) || "subject".equals(raw))
        {
            return "subjective";
        }
        if ("fill".equals(raw) || "subjective".equals(raw))
        {
            return raw;
        }
        return "choice";
    }

    private static boolean isChoiceQuestion(Map<String, Object> question)
    {
        return "choice".equals(questionType(question));
    }

    private static boolean isSubjectiveQuestion(Map<String, Object> question)
    {
        return "subjective".equals(questionType(question));
    }

    private static boolean isSelfReviewQuestion(Map<String, Object> question)
    {
        return !isChoiceQuestion(question);
    }

    private static boolean questionAnswerMatches(Map<String, Object> question, Object submitted)
    {
        if (isChoiceQuestion(question))
        {
            return intValue(submitted) == intValue(question.get("answer"));
        }
        if (isSubjectiveQuestion(question))
        {
            return false;
        }
        String answer = normalizeAnswerText(str(submitted));
        if (answer.length() == 0)
        {
            return false;
        }
        for (String candidate : answerCandidates(correctAnswerText(question)))
        {
            if (answer.equals(normalizeAnswerText(candidate)))
            {
                return true;
            }
        }
        for (String candidate : stringList(question.get("acceptableAnswers")))
        {
            if (answer.equals(normalizeAnswerText(candidate)))
            {
                return true;
            }
        }
        return false;
    }

    private static String correctAnswerText(Map<String, Object> question)
    {
        if (question == null)
        {
            return "";
        }
        if (isChoiceQuestion(question))
        {
            return optionText(question, intValue(question.get("answer")));
        }
        return valueOrDefault(question.get("answerText"), question.get("answer")).trim();
    }

    private static List<String> answerCandidates(String value)
    {
        List<String> list = new ArrayList<>();
        for (String item : str(value).split("[\\n；;|、，,]"))
        {
            String text = item.trim();
            if (text.length() > 0)
            {
                list.add(text);
            }
        }
        return list;
    }

    private static String normalizeAnswerText(String value)
    {
        return str(value).trim().replaceAll("\\s+", "").toLowerCase();
    }

    private static Map<String, Object> recordWrongQuestion(Map<String, Object> wrong, Map<String, Object> user)
    {
        if (str(wrong.get("updatedAt")).length() == 0)
        {
            wrong.put("updatedAt", now());
        }
        ensureWrongCourseMeta(wrong);
        String courseId = scopedCourseId(wrong.get("courseId"));
        String questionKey = ensureWrongQuestionKey(wrong);
        ensureWrongSourceTypes(wrong);
        Map<String, Object> existing = findWrongByQuestion(user, courseId, questionKey);
        if (existing == null)
        {
            wrong.put("mastered", false);
            wrong.put("lastWrongAt", wrong.get("updatedAt"));
            wrong.put("repeatWrongCount", Math.max(1, intValue(wrong.get("repeatWrongCount"))));
            wrongQuestions.add(wrong);
            return wrong;
        }
        mergeWrongQuestion(existing, wrong, true);
        return existing;
    }

    private static Map<String, Object> wrongFromReviewedDetail(Map<String, Object> attempt, Map<String, Object> detail, Map<String, Object> user)
    {
        String questionId = str(detail.get("id"));
        Map<String, Object> question = findById(questions, questionId);
        String questionType = question == null ? str(detail.get("questionType")) : questionType(question);
        return map(
            "id", "wrong-" + System.currentTimeMillis() + "-" + questionId,
            "userId", user == null ? null : user.get("id"),
            "questionId", questionId,
            "questionType", questionType,
            "courseId", attempt.get("courseId"),
            "courseTitle", attempt.get("courseTitle"),
            "subjectTitle", attempt.get("subjectTitle"),
            "title", attempt.get("title"),
            "type", attempt.get("type"),
            "sourceType", attempt.get("sourceType"),
            "stem", detail.get("stem"),
            "options", question == null ? new ArrayList<Object>() : question.get("options"),
            "answer", detail.get("answer"),
            "answerText", detail.get("answerText"),
            "answerImageUrl", detail.get("answerImageUrl"),
            "selected", detail.get("selected"),
            "selectedText", detail.get("selectedText"),
            "studentAnswerImageUrl", detail.get("studentAnswerImageUrl"),
            "reviewResult", detail.get("reviewResult"),
            "partialCredit", detail.get("partialCredit"),
            "analysis", detail.get("analysis"),
            "analysisImageUrl", detail.get("analysisImageUrl"),
            "videoAnalysisUrl", detail.get("videoAnalysisUrl"),
            "knowledge", question == null ? "" : question.get("knowledge"),
            "mastered", false,
            "updatedAt", now()
        );
    }

    private static String normalizeReviewResult(Object reviewResultValue, Object correctValue)
    {
        String reviewResult = str(reviewResultValue).trim().toLowerCase();
        if ("correct".equals(reviewResult) || "right".equals(reviewResult) || "ok".equals(reviewResult))
        {
            return "correct";
        }
        if ("partial".equals(reviewResult) || "half".equals(reviewResult) || "half_correct".equals(reviewResult))
        {
            return "partial";
        }
        if ("wrong".equals(reviewResult) || "incorrect".equals(reviewResult) || "bad".equals(reviewResult))
        {
            return "wrong";
        }
        return boolValue(correctValue, false) ? "correct" : "wrong";
    }

    private static String reviewResultText(Object reviewResultValue)
    {
        String reviewResult = str(reviewResultValue).trim().toLowerCase();
        if ("correct".equals(reviewResult) || "right".equals(reviewResult) || "ok".equals(reviewResult))
        {
            return "对";
        }
        if ("partial".equals(reviewResult) || "half".equals(reviewResult) || "half_correct".equals(reviewResult))
        {
            return "半对";
        }
        if ("wrong".equals(reviewResult) || "incorrect".equals(reviewResult) || "bad".equals(reviewResult))
        {
            return "错";
        }
        if ("pending".equals(reviewResult))
        {
            return "待自评";
        }
        return "";
    }

    private static void markWrongQuestionMastered(Map<String, Object> user, String courseId, String questionId)
    {
        Map<String, Object> question = findById(questions, questionId);
        String questionKey = question == null ? questionId : questionFingerprint(question);
        for (Map<String, Object> wrong : wrongQuestions)
        {
            if (sameUser(wrong, user)
                && scopedCourseId(courseId).equals(scopedCourseId(wrong.get("courseId")))
                && (questionId.equals(str(wrong.get("questionId"))) || questionKey.equals(ensureWrongQuestionKey(wrong))))
            {
                wrong.put("mastered", true);
                wrong.put("updatedAt", now());
            }
        }
    }

    private static void recalculateAttemptScore(Map<String, Object> attempt)
    {
        int correct = 0;
        int pending = 0;
        int total = 0;
        int manualReviewCount = 0;
        double earned = 0d;
        for (Map<String, Object> detail : mapList(attempt.get("details")))
        {
            total++;
            boolean manualReview = boolValue(detail.get("manualReview"), false);
            if (manualReview)
            {
                manualReviewCount++;
                pending++;
            }
            if (boolValue(detail.get("correct"), false))
            {
                correct++;
                earned += 1d;
            }
            else if ("partial".equals(str(detail.get("reviewResult"))))
            {
                earned += 0.5d;
            }
        }
        int gradable = Math.max(0, total - pending);
        attempt.put("total", total);
        attempt.put("correct", correct);
        attempt.put("manualReviewCount", manualReviewCount);
        attempt.put("score", gradable <= 0 ? 0 : Math.round((float) (earned * 100d / gradable)));
    }

    private static Map<String, Object> findWrongByQuestion(Map<String, Object> user, String courseId, String questionKey)
    {
        String expectedCourseId = scopedCourseId(courseId);
        for (Map<String, Object> wrong : wrongQuestions)
        {
            if (sameUser(wrong, user)
                && expectedCourseId.equals(scopedCourseId(wrong.get("courseId")))
                && questionKey.equals(ensureWrongQuestionKey(wrong)))
            {
                return wrong;
            }
        }
        return null;
    }

    private static boolean normalizeWrongQuestions()
    {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        boolean changed = false;
        for (Map<String, Object> wrong : wrongQuestions)
        {
            boolean hadQuestionKey = str(wrong.get("questionKey")).trim().length() > 0;
            boolean hadSourceTypes = wrong.get("sourceTypes") instanceof List;
            boolean hadRepeatWrongCount = intValue(wrong.get("repeatWrongCount")) > 0;
            boolean courseChanged = ensureWrongCourseMeta(wrong);
            String key = wrongRecordKey(wrong);
            if (!hadQuestionKey || !hadSourceTypes || !hadRepeatWrongCount || courseChanged)
            {
                changed = true;
            }
            Map<String, Object> existing = unique.get(key);
            if (existing == null)
            {
                ensureWrongSourceTypes(wrong);
                if (intValue(wrong.get("repeatWrongCount")) <= 0)
                {
                    wrong.put("repeatWrongCount", 1);
                }
                unique.put(key, wrong);
                normalized.add(wrong);
                continue;
            }
            mergeWrongQuestion(existing, wrong, false);
            changed = true;
        }
        if (changed)
        {
            wrongQuestions.clear();
            wrongQuestions.addAll(normalized);
        }
        return changed;
    }

    private static String wrongRecordKey(Map<String, Object> wrong)
    {
        return str(wrong.get("userId")) + ":" + scopedCourseId(wrong.get("courseId")) + ":" + ensureWrongQuestionKey(wrong);
    }

    private static String ensureWrongQuestionKey(Map<String, Object> wrong)
    {
        String key = str(wrong.get("questionKey")).trim();
        if (key.length() == 0)
        {
            key = str(wrong.get("questionId")).trim();
        }
        if (key.length() == 0)
        {
            key = questionFingerprint(wrong);
        }
        if (key.length() == 0)
        {
            key = str(wrong.get("id")).trim();
        }
        wrong.put("questionKey", key);
        return key;
    }

    private static String questionFingerprint(Map<String, Object> question)
    {
        String seed = normalizeQuestionText(question.get("stem")) + "|"
            + normalizeQuestionText(question.get("questionType")) + "|"
            + normalizeQuestionText(question.get("options")) + "|"
            + normalizeQuestionText(question.get("answer")) + "|"
            + normalizeQuestionText(question.get("answerText"));
        if (seed.replace("|", "").length() == 0)
        {
            return "";
        }
        return "fp-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizeQuestionText(Object value)
    {
        if (value instanceof List)
        {
            StringBuilder text = new StringBuilder();
            for (Object item : (List<?>) value)
            {
                if (text.length() > 0)
                {
                    text.append('|');
                }
                text.append(normalizeQuestionText(item));
            }
            return text.toString();
        }
        return str(value).replaceAll("\\s+", " ").trim();
    }

    private static void mergeWrongQuestion(Map<String, Object> target, Map<String, Object> source, boolean repeatedWrong)
    {
        mergeWrongSourceTypes(target, source);
        String sourceUpdatedAt = str(source.get("updatedAt"));
        String targetUpdatedAt = str(target.get("updatedAt"));
        boolean sourceIsNewer = sourceUpdatedAt.length() > 0 && sourceUpdatedAt.compareTo(targetUpdatedAt) >= 0;
        if (repeatedWrong || sourceIsNewer)
        {
            copyWrongSnapshot(target, source);
            target.put("updatedAt", sourceUpdatedAt.length() == 0 ? now() : sourceUpdatedAt);
        }
        if (repeatedWrong)
        {
            target.put("mastered", false);
            target.put("lastWrongAt", sourceUpdatedAt.length() == 0 ? now() : sourceUpdatedAt);
            target.put("repeatWrongCount", Math.max(1, intValue(target.get("repeatWrongCount"))) + 1);
            return;
        }
        target.put("repeatWrongCount", Math.max(1, intValue(target.get("repeatWrongCount"))) + Math.max(1, intValue(source.get("repeatWrongCount"))));
        target.put("retryCount", intValue(target.get("retryCount")) + intValue(source.get("retryCount")));
        target.put("retryWrongCount", intValue(target.get("retryWrongCount")) + intValue(source.get("retryWrongCount")));
    }

    private static void copyWrongSnapshot(Map<String, Object> target, Map<String, Object> source)
    {
        for (String key : Arrays.asList(
            "questionId", "questionKey", "sourceWrongId", "originType", "courseId", "courseTitle", "subjectTitle",
            "title", "type", "sourceType", "stem", "options", "answer", "selected", "analysis", "videoAnalysisUrl",
            "knowledge", "mastered", "lastRetryCorrect", "lastRetryAt"))
        {
            if (source.containsKey(key))
            {
                target.put(key, source.get(key));
            }
        }
        ensureWrongQuestionKey(target);
        ensureWrongCourseMeta(target);
    }

    private static void mergeWrongSourceTypes(Map<String, Object> target, Map<String, Object> source)
    {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        addWrongSourceTypes(types, target);
        addWrongSourceTypes(types, source);
        target.put("sourceTypes", new ArrayList<>(types));
    }

    private static List<String> ensureWrongSourceTypes(Map<String, Object> wrong)
    {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        addWrongSourceTypes(types, wrong);
        List<String> list = new ArrayList<>(types);
        wrong.put("sourceTypes", list);
        return list;
    }

    private static void addWrongSourceTypes(Set<String> types, Map<String, Object> wrong)
    {
        if (wrong == null)
        {
            return;
        }
        for (String type : stringList(wrong.get("sourceTypes")))
        {
            addWrongSourceType(types, type);
        }
        addWrongSourceType(types, wrong.get("type"));
        addWrongSourceType(types, wrong.get("originType"));
    }

    private static void addWrongSourceType(Set<String> types, Object value)
    {
        String type = str(value).trim();
        if (type.length() > 0)
        {
            types.add(type);
        }
    }

    private static String analysisVideoUrl(Map<String, Object> question)
    {
        if (question == null)
        {
            return "";
        }
        String url = str(question.get("videoAnalysisUrl")).trim();
        if (url.length() == 0)
        {
            url = str(question.get("analysisVideoUrl")).trim();
        }
        if (url.length() == 0)
        {
            url = str(question.get("explainVideoUrl")).trim();
        }
        return url;
    }

    private static String analysisImageUrl(Map<String, Object> question)
    {
        if (question == null)
        {
            return "";
        }
        String url = str(question.get("analysisImageUrl")).trim();
        if (url.length() == 0)
        {
            url = str(question.get("imageAnalysisUrl")).trim();
        }
        if (url.length() == 0)
        {
            url = str(question.get("explainImageUrl")).trim();
        }
        return url;
    }

    private static List<Map<String, Object>> wrongbookItems(List<Map<String, Object>> source, String sourceFilter)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> wrong : source)
        {
            if (matchesWrongSource(wrong, sourceFilter))
            {
                list.add(wrongbookItem(wrong));
            }
        }
        list.sort((a, b) -> str(b.get("updatedAt")).compareTo(str(a.get("updatedAt"))));
        return list;
    }

    private static Map<String, Object> wrongbookItem(Map<String, Object> wrong)
    {
        Map<String, Object> item = new LinkedHashMap<>(wrong);
        item.put("sourceType", sourceLabel(wrong.get("type")));
        item.put("sourceTags", wrongSourceTags(wrong));
        item.put("weak", isWeakWrong(wrong));
        item.put("selectedText", optionText(wrong, intValue(wrong.get("selected"))));
        item.put("answerText", optionText(wrong, intValue(wrong.get("answer"))));
        item.put("statusText", Boolean.TRUE.equals(wrong.get("mastered")) ? "已掌握" : "未掌握");
        return item;
    }

    private static List<String> wrongSourceTags(Map<String, Object> wrong)
    {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addTag(tags, wrong.get("knowledge"));
        String type = str(wrong.get("type"));
        List<String> sourceTypes = ensureWrongSourceTypes(wrong);
        if (!sourceTypes.isEmpty())
        {
            for (String sourceType : sourceTypes)
            {
                addTag(tags, sourceLabel(sourceType));
            }
        }
        else
        {
            String originType = str(wrong.get("originType"));
            if (originType.length() > 0 && !originType.equals(type))
            {
                addTag(tags, sourceLabel(originType));
            }
            addTag(tags, sourceLabel(type));
        }
        if (isWrongRetryType(type) || intValue(wrong.get("retryCount")) > 0 || intValue(wrong.get("retryWrongCount")) > 0)
        {
            addTag(tags, "错题重练");
        }
        if (tags.isEmpty())
        {
            addTag(tags, wrong.get("title"));
        }
        return new ArrayList<>(tags);
    }

    private static void addTag(Set<String> tags, Object value)
    {
        String text = str(value).trim();
        if (text.length() > 0)
        {
            tags.add(text);
        }
    }

    private static boolean matchesWrongSource(Map<String, Object> wrong, String sourceFilter)
    {
        String filter = str(sourceFilter).trim();
        if (filter.length() == 0 || "全部".equals(filter))
        {
            return true;
        }
        if ("最新错题".equals(filter))
        {
            return isLatestWrong(wrong);
        }
        if (filter.equals(sourceLabel(wrong.get("type"))))
        {
            return true;
        }
        for (String sourceType : ensureWrongSourceTypes(wrong))
        {
            if (filter.equals(sourceLabel(sourceType)))
            {
                return true;
            }
        }
        return wrongSourceTags(wrong).contains(filter);
    }

    private static boolean isLatestWrong(Map<String, Object> wrong)
    {
        try
        {
            return LocalDateTime.parse(str(wrong.get("updatedAt"))).isAfter(LocalDateTime.now().minusHours(24));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static int countLatestWrong(List<Map<String, Object>> list)
    {
        int total = 0;
        for (Map<String, Object> wrong : list)
        {
            if (isLatestWrong(wrong))
            {
                total++;
            }
        }
        return total;
    }

    private static String latestWrongAt(List<Map<String, Object>> list)
    {
        String latest = "";
        for (Map<String, Object> wrong : list)
        {
            String updatedAt = str(wrong.get("updatedAt"));
            if (updatedAt.compareTo(latest) > 0)
            {
                latest = updatedAt;
            }
        }
        return latest;
    }

    private static boolean isWeakWrong(Map<String, Object> wrong)
    {
        return wrongSourceTags(wrong).size() >= 3 || intValue(wrong.get("retryWrongCount")) >= 2 || intValue(wrong.get("retryCount")) >= 3;
    }

    private static int countWrongByMastered(List<Map<String, Object>> list, boolean mastered)
    {
        int total = 0;
        for (Map<String, Object> wrong : list)
        {
            if (Boolean.TRUE.equals(wrong.get("mastered")) == mastered)
            {
                total++;
            }
        }
        return total;
    }

    private static List<Map<String, Object>> weakWrongItems(List<Map<String, Object>> source, String sourceFilter)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> wrong : source)
        {
            if (isWeakWrong(wrong) && matchesWrongSource(wrong, sourceFilter))
            {
                list.add(wrongbookItem(wrong));
            }
        }
        list.sort((a, b) -> str(b.get("updatedAt")).compareTo(str(a.get("updatedAt"))));
        return list;
    }

    private static List<Map<String, Object>> wrongSourceStats(List<Map<String, Object>> source)
    {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (String label : Arrays.asList("全部", "最新错题", "章节扫雷", "复习测试", "真题讲练", "错题重练"))
        {
            int total = 0;
            for (Map<String, Object> wrong : source)
            {
                if (matchesWrongSource(wrong, label))
                {
                    total++;
                }
            }
            stats.add(map("label", label, "value", total));
        }
        return stats;
    }

    private static List<Map<String, Object>> attemptRecords(List<Map<String, Object>> source, String sourceFilter)
    {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> attempt : source)
        {
            if (matchesAttemptSource(attempt, sourceFilter))
            {
                records.add(attemptRecord(attempt));
            }
        }
        records.sort((a, b) -> str(b.get("completedAt")).compareTo(str(a.get("completedAt"))));
        return records;
    }

    private static boolean matchesAttemptSource(Map<String, Object> attempt, String sourceFilter)
    {
        String filter = str(sourceFilter).trim();
        return filter.length() == 0 || "全部".equals(filter) || filter.equals(sourceLabel(attempt.get("type")));
    }

    private static Map<String, Object> attemptRecord(Map<String, Object> attempt)
    {
        int total = intValue(attempt.get("total"));
        int correct = intValue(attempt.get("correct"));
        int score = intValue(attempt.get("score"));
        String sourceType = sourceLabel(attempt.get("type"));
        return map(
            "id", attempt.get("id"),
            "courseId", attempt.get("courseId"),
            "courseTitle", displayCourseTitle(attempt),
            "subjectTitle", displaySubjectTitle(attempt),
            "title", attempt.get("title"),
            "type", attempt.get("type"),
            "sourceType", sourceType,
            "score", score,
            "scoreText", score + "/100 分",
            "total", total,
            "correct", correct,
            "wrongCount", Math.max(0, total - correct),
            "completedAt", attempt.get("createdAt"),
            "details", attemptDetailRows(attempt)
        );
    }

    private static List<Map<String, Object>> attemptDetailRows(Map<String, Object> attempt)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> details = mapList(attempt.get("details"));
        int total = details.size();
        for (int i = 0; i < details.size(); i++)
        {
            Map<String, Object> detail = details.get(i);
            Map<String, Object> question = findById(questions, str(detail.get("id")));
            String type = questionType(question == null ? detail : question);
            String myAnswer = "choice".equals(type) ? answerLetter(detail.get("selected")) : str(detail.get("selectedText")).trim();
            String correctAnswer = "choice".equals(type) ? answerLetter(detail.get("answer")) : str(detail.get("answerText")).trim();
            rows.add(map(
                "questionNo", i + 1,
                "total", total,
                "stem", detail.get("stem"),
                "questionType", type,
                "myAnswer", myAnswer.length() == 0 ? "--" : myAnswer,
                "correctAnswer", correctAnswer.length() == 0 ? "--" : correctAnswer,
                "myAnswerText", str(detail.get("selectedText")).trim().length() > 0 ? detail.get("selectedText") : myAnswer,
                "correctAnswerText", str(detail.get("answerText")).trim().length() > 0 ? detail.get("answerText") : (question == null ? correctAnswer : optionText(question, intValue(detail.get("answer")))),
                "correct", Boolean.TRUE.equals(detail.get("correct")),
                "manualReview", Boolean.TRUE.equals(detail.get("manualReview")),
                "selfReviewed", boolValue(detail.get("selfReviewed"), false),
                "selectedText", detail.get("selectedText"),
                "studentAnswerImageUrl", detail.get("studentAnswerImageUrl"),
                "answerImageUrl", detail.get("answerImageUrl"),
                "reviewResult", detail.get("reviewResult"),
                "reviewResultText", reviewResultText(detail.get("reviewResult")),
                "partialCredit", detail.get("partialCredit"),
                "noUpload", boolValue(detail.get("noUpload"), false),
                "skipped", boolValue(detail.get("skipped"), false),
                "reviewedAt", detail.get("reviewedAt"),
                "analysis", detail.get("analysis"),
                "analysisImageUrl", detail.get("analysisImageUrl"),
                "videoAnalysisUrl", detail.get("videoAnalysisUrl")
            ));
        }
        return rows;
    }

    private static List<Map<String, Object>> attemptCourseCounts(List<Map<String, Object>> records)
    {
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (Map<String, Object> record : records)
        {
            String label = str(record.get("subjectTitle"));
            if (label.length() == 0)
            {
                label = str(record.get("courseTitle"));
            }
            if (label.length() == 0)
            {
                label = "高考数学";
            }
            buckets.put(label, buckets.containsKey(label) ? buckets.get(label) + 1 : 1);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : buckets.entrySet())
        {
            list.add(map("label", entry.getKey(), "value", entry.getValue()));
        }
        return list;
    }

    private static Map<String, Object> wrongRetryPaper(List<Map<String, Object>> source, int count, String sourceFilter)
    {
        int limit = count <= 0 ? 5 : Math.min(count, 20);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> wrong : source)
        {
            if (!Boolean.TRUE.equals(wrong.get("mastered")) && matchesWrongSource(wrong, sourceFilter))
            {
                candidates.add(wrong);
            }
        }
        candidates.sort((a, b) -> str(b.get("updatedAt")).compareTo(str(a.get("updatedAt"))));
        List<Map<String, Object>> uniqueCandidates = new ArrayList<>();
        Set<String> seenQuestions = new LinkedHashSet<>();
        for (Map<String, Object> wrong : candidates)
        {
            String questionKey = ensureWrongQuestionKey(wrong);
            if (seenQuestions.contains(questionKey))
            {
                continue;
            }
            seenQuestions.add(questionKey);
            uniqueCandidates.add(wrong);
        }
        List<Map<String, Object>> paperQuestions = new ArrayList<>();
        List<String> sourceWrongIds = new ArrayList<>();
        for (Map<String, Object> wrong : uniqueCandidates)
        {
            Map<String, Object> question = publicQuestionForWrong(wrong);
            if (question == null)
            {
                continue;
            }
            paperQuestions.add(question);
            sourceWrongIds.add(str(wrong.get("id")));
            if (paperQuestions.size() >= limit)
            {
                break;
            }
        }
        return map(
            "title", "错题重练",
            "type", "wrongRetry",
            "count", paperQuestions.size(),
            "requestedCount", limit,
            "availableCount", uniqueCandidates.size(),
            "sourceWrongIds", sourceWrongIds,
            "questions", paperQuestions
        );
    }

    private static Map<String, Object> publicQuestionForWrong(Map<String, Object> wrong)
    {
        Map<String, Object> question = findById(questions, str(wrong.get("questionId")));
        if (question != null)
        {
            Map<String, Object> item = new LinkedHashMap<>(question);
            item.remove("answer");
            item.remove("answerText");
            item.remove("answerImageUrl");
            item.remove("acceptableAnswers");
            return item;
        }
        if (str(wrong.get("stem")).length() == 0)
        {
            return null;
        }
        return map(
            "id", wrong.get("questionId"),
            "stem", wrong.get("stem"),
            "questionType", wrong.get("questionType"),
            "options", wrong.get("options"),
            "knowledge", wrong.get("knowledge"),
            "analysisImageUrl", wrong.get("analysisImageUrl"),
            "videoAnalysisUrl", wrong.get("videoAnalysisUrl")
        );
    }

    private static void updateWrongRetryStats(String type, List<String> sourceWrongIds, List<Map<String, Object>> details, Map<String, Object> user, String courseId)
    {
        if (!isWrongRetryType(type))
        {
            return;
        }
        String expectedCourseId = scopedCourseId(courseId);
        for (int i = 0; i < sourceWrongIds.size(); i++)
        {
            Map<String, Object> wrong = findById(wrongQuestions, sourceWrongIds.get(i));
            if (wrong == null || !sameUser(wrong, user) || !expectedCourseId.equals(scopedCourseId(wrong.get("courseId"))))
            {
                continue;
            }
            boolean pendingReview = i < details.size() && boolValue(details.get(i).get("manualReview"), false);
            if (pendingReview)
            {
                continue;
            }
            boolean correct = i < details.size() && Boolean.TRUE.equals(details.get(i).get("correct"));
            wrong.put("retryCount", intValue(wrong.get("retryCount")) + 1);
            if (!correct)
            {
                wrong.put("retryWrongCount", intValue(wrong.get("retryWrongCount")) + 1);
            }
            wrong.put("lastRetryCorrect", correct);
            wrong.put("lastRetryAt", now());
            wrong.put("updatedAt", now());
        }
    }

    private static boolean isWrongRetryType(Object type)
    {
        String value = str(type);
        return "wrongRetry".equals(value) || "wrong-retry".equals(value) || "错题重练".equals(value);
    }

    private static String sourceLabel(Object type)
    {
        String value = str(type);
        if ("quiz".equals(value) || "章节扫雷".equals(value))
        {
            return "章节扫雷";
        }
        if ("reinforce".equals(value) || "复习测试".equals(value))
        {
            return "复习测试";
        }
        if (isWrongRetryType(value))
        {
            return "错题重练";
        }
        return "真题讲练";
    }

    private static String answerLetter(Object value)
    {
        int index = intValue(value);
        if (index < 0)
        {
            return "--";
        }
        return String.valueOf((char) ('A' + index));
    }

    private static String optionText(Map<String, Object> source, int index)
    {
        Object optionsValue = source.get("options");
        if (optionsValue instanceof List)
        {
            List<?> options = (List<?>) optionsValue;
            if (index >= 0 && index < options.size())
            {
                return answerLetter(index) + ". " + str(options.get(index));
            }
        }
        return answerLetter(index);
    }

    private static String resolveCourseTitle(Object courseId)
    {
        String id = str(courseId);
        if (id.length() == 0)
        {
            return "";
        }
        Map<String, Object> course = findCourse(id);
        return course == null ? id : stripCourseYear(course.get("courseName"));
    }

    private static String resolveSubjectTitle(Object courseId)
    {
        String id = str(courseId);
        if (id.length() == 0)
        {
            return "高考数学";
        }
        Map<String, Object> course = findCourse(id);
        if (course == null)
        {
            return "高考数学";
        }
        String title = str(course.get("title"));
        return title.length() == 0 ? stripCourseYear(course.get("courseName")) : title;
    }

    private static String displayCourseTitle(Map<String, Object> attempt)
    {
        String title = str(attempt.get("courseTitle"));
        if (title.length() > 0)
        {
            return title;
        }
        title = resolveCourseTitle(attempt.get("courseId"));
        return title.length() == 0 ? "高考数学" : title;
    }

    private static String displaySubjectTitle(Map<String, Object> attempt)
    {
        String title = str(attempt.get("subjectTitle"));
        if (title.length() > 0)
        {
            return title;
        }
        return resolveSubjectTitle(attempt.get("courseId"));
    }

    private static Map<String, Object> getStudySummary()
    {
        return map(
            "sections", list(
                map("title", "章节扫雷", "items", list(map("label", "刷题数", "value", "186道"), map("label", "正确", "value", "142道"), map("label", "正确率", "value", "76%"), map("label", "平均得分", "value", "78分"))),
                map("title", "章节测评", "items", list(map("label", "测评次数", "value", "18次"), map("label", "平均得分", "value", "77分")), "details", list(
                    map("title", "集合", "count", "测试次数8次", "score", "平均74分", "records", list(
                        map("name", "入门测", "result", "正确14题，错误6题", "score", "72分"),
                        map("name", "章节测试", "result", "正确16题，错误4题", "score", "76分")
                    )),
                    map("title", "数列", "count", "测试次数10次", "score", "平均80分", "records", list(
                        map("name", "通项公式", "result", "正确17题，错误3题", "score", "82分"),
                        map("name", "求和训练", "result", "正确15题，错误5题", "score", "78分")
                    ))
                )),
                map("title", "复习加强统计", "items", list(map("label", "复习课程完成情况", "value", "68%"), map("label", "测评统计", "value", "完成6次，平均72分")), "details", list(
                    map("title", "第1次复习试卷", "count", "完成", "score", "70分", "records", list(map("name", "错题复盘", "result", "正确12题，错误5题", "score", "70分"))),
                    map("title", "第2次复习试卷", "count", "完成", "score", "74分", "records", list(map("name", "综合测试", "result", "正确15题，错误4题", "score", "74分")))
                )),
                map("title", "思维技巧", "items", list(map("label", "英语完成度", "value", "30%"), map("label", "训练做题", "value", "96道"), map("label", "正确", "value", "73道"), map("label", "错误", "value", "23道"), map("label", "平均得分", "value", "76分"))),
                map("title", "英语外语科目", "items", list(map("label", "单词完成数量", "value", "428个"), map("label", "今日完成", "value", "36个")))
            ),
            "plateScores", list(
                plate("章节扫雷", 78),
                plate("章节测评", 74),
                plate("复习情况", 62),
                plate("思维技巧", 86),
                plate("真题讲练", 58)
            )
        );
    }

    private static Map<String, Object> learningStats(Map<String, Object> user, String courseId)
    {
        int totalSeconds = 0;
        int todaySeconds = 0;
        int weekSeconds = 0;
        Set<String> days = new LinkedHashSet<>();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> progress : lessonProgress.values())
        {
            if (!sameUser(progress, user))
            {
                continue;
            }
            String progressCourseId = str(progress.get("courseId"));
            if (courseId.length() > 0 && progressCourseId.length() > 0 && !courseId.equals(progressCourseId))
            {
                continue;
            }
            int seconds = (int) Math.round(doubleValue(progress.get("currentTime")));
            totalSeconds += seconds;
            LocalDate day = datePart(str(progress.get("updatedAt")));
            if (day != null)
            {
                days.add(day.toString());
                if (day.equals(today))
                {
                    todaySeconds += seconds;
                }
                if (!day.isBefore(today.minusDays(6)))
                {
                    weekSeconds += seconds;
                }
            }
        }
        return map(
            "totalText", secondsText(totalSeconds),
            "todayText", secondsText(todaySeconds),
            "weekText", secondsText(weekSeconds),
            "days", days.size(),
            "records", learningRecords(user)
        );
    }

    private static String studyDurationText(Map<String, Object> user, String courseId)
    {
        return str(learningStats(user, courseId).get("totalText"));
    }

    private static List<Map<String, Object>> learningRecords(Map<String, Object> user)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> progress : lessonProgress.values())
        {
            if (sameUser(progress, user))
            {
                result.add(map(
                    "lessonTitle", progress.get("lessonTitle"),
                    "duration", secondsText((int) Math.round(doubleValue(progress.get("currentTime")))),
                    "updatedAt", progress.get("updatedAt")
                ));
            }
        }
        result.sort((a, b) -> str(b.get("updatedAt")).compareTo(str(a.get("updatedAt"))));
        return result.size() > 20 ? result.subList(0, 20) : result;
    }

    private static List<Map<String, Object>> recentPracticeRows(List<Map<String, Object>> userAttempts)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> copy = new ArrayList<>(userAttempts);
        copy.sort((a, b) -> str(b.get("createdAt")).compareTo(str(a.get("createdAt"))));
        for (Map<String, Object> attempt : copy)
        {
            int wrong = Math.max(0, intValue(attempt.get("total")) - intValue(attempt.get("correct")));
            rows.add(map(
                "title", attempt.get("title"),
                "type", attempt.get("type"),
                "score", attempt.get("score"),
                "averageScore", attempt.get("score"),
                "wrongCount", wrong,
                "createdAt", attempt.get("createdAt")
            ));
            if (rows.size() >= 8)
            {
                break;
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> attemptRowsByType(List<Map<String, Object>> userAttempts, String type)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> attempt : userAttempts)
        {
            if (type.equals(str(attempt.get("type"))))
            {
                rows.add(attempt);
            }
        }
        rows.sort((a, b) -> str(b.get("createdAt")).compareTo(str(a.get("createdAt"))));
        return rows.size() > 8 ? rows.subList(0, 8) : rows;
    }

    private static Map<String, Object> resolveStudyUser(Map<String, Object> requester, String userId)
    {
        String targetId = str(userId).trim();
        if (targetId.length() == 0)
        {
            return requester;
        }
        Map<String, Object> target = findById(users, targetId);
        if (target == null || requester == null)
        {
            return requester;
        }
        if (targetId.equals(str(requester.get("id"))) || canViewStudent(requester, targetId))
        {
            return target;
        }
        return requester;
    }

    private static boolean canViewStudent(Map<String, Object> requester, String studentUserId)
    {
        String role = str(requester.get("role"));
        if ("admin".equals(role) || "agency_admin".equals(role) || "teacher".equals(role))
        {
            return true;
        }
        for (Map<String, Object> binding : studentBindings)
        {
            if (str(requester.get("id")).equals(str(binding.get("ownerUserId"))) && studentUserId.equals(str(binding.get("studentUserId"))))
            {
                return true;
            }
        }
        Map<String, Object> student = findById(users, studentUserId);
        if (student != null && str(requester.get("id")).equals(str(student.get("referrerUserId"))))
        {
            return true;
        }
        return false;
    }

    private static LocalDate datePart(String dateTime)
    {
        if (dateTime.length() < 10)
        {
            return null;
        }
        try
        {
            return LocalDate.parse(dateTime.substring(0, 10));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String secondsText(int seconds)
    {
        int safe = Math.max(0, seconds);
        int hours = safe / 3600;
        int minutes = (safe % 3600) / 60;
        int sec = safe % 60;
        if (hours > 0)
        {
            return hours + "小时" + minutes + "分" + sec + "秒";
        }
        if (minutes > 0)
        {
            return minutes + "分" + sec + "秒";
        }
        return sec + "秒";
    }

    private static Map<String, Object> agencySummaryData(String userId)
    {
        enrichActivationCodes();
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Map<String, Object>> usedStudents = new ArrayList<>();
        int used = 0;
        int assignedUnused = 0;
        int locked = 0;
        for (Map<String, Object> card : activationCodes)
        {
            if (!userId.equals(str(card.get("ownerUserId"))))
            {
                continue;
            }
            cards.add(card);
            if ("used".equals(card.get("status")))
            {
                used++;
                Map<String, Object> course = findCourse(str(card.get("courseId")));
                usedStudents.add(map(
                    "studentName", card.get("studentName"),
                    "subject", course == null ? card.get("courseTitle") : course.get("title"),
                    "region", card.get("region"),
                    "activatedAt", card.get("activatedAt"),
                    "code", card.get("code"),
                    "courseTitle", card.get("courseTitle")
                ));
            }
            if (activationCardLocked(card))
            {
                locked++;
            }
            if (!"used".equals(card.get("status")))
            {
                assignedUnused++;
            }
        }
        Map<String, Object> user = findById(users, userId);
        return map(
            "agency", user == null ? new LinkedHashMap<String, Object>() : publicUser(user),
            "quota", user == null ? cards.size() : intValue(user.get("activationQuota")),
            "totalCodes", cards.size(),
            "activatedCount", used,
            "unusedCount", assignedUnused,
            "lockedCount", locked,
            "userCount", agencyUserCount(userId),
            "openPeopleCount", agencyUserCount(userId),
            "activatedCourseCount", agencyActivatedCourseCount(userId),
            "referralCount", agencyUserCount(userId),
            "boundStudentCount", usedStudents.size(),
            "codes", cards,
            "students", usedStudents
        );
    }

    private static Map<String, Object> courseKindStats()
    {
        int trial = 0;
        int full = 0;
        for (Map<String, Object> course : courses)
        {
            if ("trial".equals(course.get("kind")))
            {
                trial++;
            }
            else
            {
                full++;
            }
        }
        return map("trial", trial, "full", full);
    }

    private static Map<String, Object> userStats()
    {
        int agencyCount = 0;
        int agencyWithQuota = 0;
        int formalStudents = 0;
        int trialStudents = 0;
        Set<String> openPeople = new LinkedHashSet<>();
        for (Map<String, Object> enrollment : enrollments)
        {
            if (isEnrollmentOpen(enrollment))
            {
                openPeople.add(str(enrollment.get("userId")));
            }
        }
        for (Map<String, Object> user : users)
        {
            String role = str(user.get("role"));
            if ("agency_admin".equals(role))
            {
                agencyCount++;
                if (intValue(user.get("activationQuota")) > 0)
                {
                    agencyWithQuota++;
                }
            }
            else if ("student".equals(role) || role.length() == 0)
            {
                if (openPeople.contains(str(user.get("id"))))
                {
                    formalStudents++;
                }
                else
                {
                    trialStudents++;
                }
            }
        }
        return map(
            "agencyCount", agencyCount,
            "agencyWithQuota", agencyWithQuota,
            "formalStudents", formalStudents,
            "trialStudents", trialStudents,
            "openPeopleCount", openPeople.size()
        );
    }

    private static int agencyActivatedCourseCount(String agencyUserId)
    {
        Set<String> courseIds = new LinkedHashSet<>();
        for (Map<String, Object> card : activationCodes)
        {
            if (agencyUserId.equals(str(card.get("ownerUserId"))) && "used".equals(card.get("status")) && str(card.get("courseId")).length() > 0)
            {
                courseIds.add(str(card.get("courseId")));
            }
        }
        return courseIds.size();
    }

    private static List<Map<String, Object>> agencyStats()
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> user : users)
        {
            if ("agency_admin".equals(str(user.get("role"))))
            {
                result.add(agencySummaryData(str(user.get("id"))));
            }
        }
        return result;
    }

    private static int agencyUserCount(String agencyUserId)
    {
        Set<String> userIds = new LinkedHashSet<>();
        for (Map<String, Object> order : orders)
        {
            if (agencyUserId.equals(str(order.get("agencyUserId"))) || agencyUserId.equals(str(order.get("ownerUserId"))))
            {
                String userId = str(order.get("userId"));
                if (userId.length() > 0)
                {
                    userIds.add(userId);
                }
            }
        }
        for (Map<String, Object> card : activationCodes)
        {
            if (agencyUserId.equals(str(card.get("ownerUserId"))))
            {
                String userId = str(card.get("usedByUserId"));
                if (userId.length() > 0)
                {
                    userIds.add(userId);
                }
            }
        }
        return userIds.size();
    }

    private static Map<String, Object> activationStats()
    {
        enrichActivationCodes();
        int assigned = 0;
        int unassigned = 0;
        int used = 0;
        int assignedUnused = 0;
        int locked = 0;
        int available = 0;
        int activeUsed = 0;
        int expired = 0;
        int closedAuthorization = 0;
        Set<String> usedCourseIds = new LinkedHashSet<>();
        for (Map<String, Object> card : activationCodes)
        {
            boolean hasOwner = str(card.get("ownerUserId")).length() > 0;
            boolean cardUsed = "used".equals(card.get("status"));
            boolean cardLocked = activationCardLocked(card);
            boolean cardExpired = activationCardExpired(card);
            boolean cardClosed = activationAuthorizationClosed(card);
            if (hasOwner)
            {
                assigned++;
            }
            else
            {
                unassigned++;
            }
            if (cardUsed)
            {
                used++;
                if (str(card.get("courseId")).length() > 0)
                {
                    usedCourseIds.add(str(card.get("courseId")));
                }
            }
            if (activationCardActiveUsed(card))
            {
                activeUsed++;
            }
            if (cardExpired)
            {
                expired++;
            }
            if (cardClosed)
            {
                closedAuthorization++;
            }
            if (cardLocked)
            {
                locked++;
            }
            if (hasOwner && !cardUsed)
            {
                assignedUnused++;
            }
            if (!cardUsed && !cardLocked)
            {
                available++;
            }
        }
        return map(
            "total", activationCodes.size(),
            "generated", activationCodes.size(),
            "assigned", assigned,
            "unassigned", unassigned,
            "used", used,
            "usedCourseCount", usedCourseIds.size(),
            "assignedUnused", assignedUnused,
            "locked", locked,
            "available", available,
            "activeUsed", activeUsed,
            "expired", expired,
            "closedAuthorization", closedAuthorization
        );
    }

    private static void enrichActivationCodes()
    {
        for (Map<String, Object> card : activationCodes)
        {
            enrichActivationCard(card);
        }
    }

    private static void enrichActivationCard(Map<String, Object> card)
    {
        normalizeActivationCardState(card);
        putIfPresent(card, card, "courseId");
        putIfPresent(card, card, "ownerUserId");
        putIfPresent(card, card, "cardType");
        boolean assigned = str(card.get("ownerUserId")).length() > 0;
        card.put("assigned", assigned);
        card.put("authorizationClosed", activationAuthorizationClosed(card));
        card.put("expired", activationCardExpired(card));
        card.put("activeUsed", activationCardActiveUsed(card));
        card.put("displayStatus", activationDisplayStatus(card));
    }

    private static void normalizeActivationCardState(Map<String, Object> card)
    {
        boolean locked = activationCardLocked(card);
        String status = str(card.get("status")).trim();
        if ("disabled".equals(status))
        {
            status = str(card.get("usedByUserId")).length() > 0 || str(card.get("activatedAt")).length() > 0 ? "used" : "available";
        }
        if (!"used".equals(status))
        {
            status = str(card.get("usedByUserId")).length() > 0 || str(card.get("activatedAt")).length() > 0 ? "used" : "available";
        }
        card.put("status", status);
        card.put("locked", locked);
    }

    private static boolean activationCardLocked(Map<String, Object> card)
    {
        return card != null && (Boolean.TRUE.equals(card.get("locked")) || "disabled".equals(str(card.get("status"))));
    }

    private static boolean activationAuthorizationClosed(Map<String, Object> card)
    {
        return card != null && Boolean.TRUE.equals(card.get("authorizationClosed"));
    }

    private static boolean activationCardExpired(Map<String, Object> card)
    {
        return card != null
            && "used".equals(card.get("status"))
            && !activationAuthorizationClosed(card)
            && isExpired(str(card.get("expiresAt")));
    }

    private static boolean activationCardActiveUsed(Map<String, Object> card)
    {
        return card != null
            && "used".equals(card.get("status"))
            && !activationAuthorizationClosed(card)
            && !activationCardExpired(card);
    }

    private static String activationDisplayStatus(Map<String, Object> card)
    {
        if (activationAuthorizationClosed(card))
        {
            return "已关闭授权";
        }
        if (activationCardExpired(card))
        {
            return "已过期";
        }
        if (activationCardLocked(card))
        {
            return "已锁定";
        }
        if ("used".equals(card.get("status")))
        {
            return "已使用";
        }
        if (str(card.get("ownerUserId")).length() > 0)
        {
            return "已分配";
        }
        return "未分配";
    }

    private static Map<String, Object> studentCourseSummary(String userId)
    {
        List<Map<String, Object>> openCourses = new ArrayList<>();
        String primaryCourseId = "";
        String grade = "";
        String region = "";
        for (Map<String, Object> enrollment : enrollments)
        {
            if (!userId.equals(str(enrollment.get("userId"))) || !isEnrollmentOpen(enrollment))
            {
                continue;
            }
            Map<String, Object> course = findCourse(str(enrollment.get("courseId")));
            String courseId = str(enrollment.get("courseId"));
            if (primaryCourseId.length() == 0)
            {
                primaryCourseId = courseId;
            }
            if (grade.length() == 0)
            {
                grade = str(enrollment.get("grade"));
            }
            if (region.length() == 0)
            {
                region = str(enrollment.get("region"));
            }
            openCourses.add(map(
                "id", courseId,
                "courseId", courseId,
            "title", course == null ? courseId : stripCourseYear(course.get("title")),
            "courseName", course == null ? courseId : stripCourseYear(course.get("courseName"))
            ));
        }
        Map<String, Object> user = findById(users, userId);
        if (grade.length() == 0 && user != null)
        {
            grade = str(user.get("grade"));
        }
        if (region.length() == 0 && user != null)
        {
            region = str(user.get("region"));
        }
        return map("courses", openCourses, "openCourses", openCourses, "primaryCourseId", primaryCourseId, "grade", grade, "region", region);
    }

    private static Map<String, Object> studentLearningSnapshot(String userId)
    {
        Map<String, Object> user = findById(users, userId);
        List<Map<String, Object>> userAttempts = filterByUser(attempts, user);
        int totalScore = 0;
        for (Map<String, Object> attempt : userAttempts)
        {
            totalScore += intValue(attempt.get("score"));
        }
        int avg = userAttempts.isEmpty() ? 0 : Math.round(totalScore / (float) userAttempts.size());
        int openCourses = 0;
        for (Map<String, Object> enrollment : enrollments)
        {
            if (userId.equals(str(enrollment.get("userId"))) && isEnrollmentOpen(enrollment))
            {
                openCourses++;
            }
        }
        int ratingCount = 0;
        for (Map<String, Object> rating : lessonRatings)
        {
            if (userId.equals(str(rating.get("userId"))))
            {
                ratingCount++;
            }
        }
        int wrongCount = 0;
        for (Map<String, Object> wrong : wrongQuestions)
        {
            if (userId.equals(str(wrong.get("userId"))))
            {
                wrongCount++;
            }
        }
        int progress = averageProgressForUser(userId);
        return map(
            "courseCount", openCourses,
            "attemptCount", userAttempts.size(),
            "averageScore", avg,
            "studyTime", studyDurationText(user, ""),
            "ratingCount", ratingCount,
            "wrongCount", wrongCount,
            "progressText", progress > 0 ? progress + "%" : "暂无"
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ratingStats()
    {
        Map<Integer, Integer> totalCounts = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++)
        {
            totalCounts.put(i, 0);
        }

        List<Map<String, Object>> groups = scoreGroups();
        Map<String, Map<String, Object>> groupByRange = new LinkedHashMap<>();
        Map<String, Set<String>> groupStudents = new LinkedHashMap<>();
        for (Map<String, Object> group : groups)
        {
            String range = str(group.get("range"));
            groupByRange.put(range, group);
            groupStudents.put(range, new LinkedHashSet<>());
        }

        for (Map<String, Object> item : lessonRatings)
        {
            int rating = intValue(item.get("rating"));
            if (rating < 1 || rating > 5)
            {
                continue;
            }
            totalCounts.put(rating, totalCounts.get(rating) + 1);

            String range = scoreRange(recentExamScoreForRating(item));
            Map<String, Object> group = groupByRange.get(range);
            if (group == null)
            {
                group = map("range", range, "students", 0, "counts", countsMap(new int[]{0, 0, 0, 0, 0}));
                groupByRange.put(range, group);
                groupStudents.put(range, new LinkedHashSet<>());
                groups.add(group);
            }
            Map<Integer, Integer> counts = (Map<Integer, Integer>) group.get("counts");
            counts.put(rating, counts.get(rating) + 1);
            String studentKey = str(item.get("userId"));
            if (studentKey.length() == 0)
            {
                studentKey = "rating:" + str(item.get("id"));
            }
            groupStudents.get(range).add(studentKey);
        }
        for (Map<String, Object> group : groups)
        {
            group.put("students", groupStudents.get(str(group.get("range"))).size());
        }

        int count = 0;
        int weighted = 0;
        for (Map.Entry<Integer, Integer> entry : totalCounts.entrySet())
        {
            count += entry.getValue();
            weighted += entry.getKey() * entry.getValue();
        }
        return map(
            "average", count == 0 ? "0.0" : String.format("%.1f", weighted / (float) count),
            "chapterTotal", count,
            "totalCounts", totalCounts,
            "groups", groups,
            "groupBasis", "activationRecentExamScore",
            "courses", courseRatingBreakdown(),
            "subjects", ratingBreakdown("subjectTitle"),
            "chapters", ratingBreakdown("chapterTitle"),
            "lessons", ratingBreakdown("lessonTitle"),
            "details", ratingLessonDetails()
        );
    }

    private static List<Map<String, Object>> scoreGroups()
    {
        return list(
            map("range", "30以内", "students", 0, "counts", countsMap(new int[]{0, 0, 0, 0, 0})),
            map("range", "30-50", "students", 0, "counts", countsMap(new int[]{0, 0, 0, 0, 0})),
            map("range", "50-70", "students", 0, "counts", countsMap(new int[]{0, 0, 0, 0, 0})),
            map("range", "70+", "students", 0, "counts", countsMap(new int[]{0, 0, 0, 0, 0}))
        );
    }

    private static Map<Integer, Integer> countsMap(int[] values)
    {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++)
        {
            counts.put(i, i <= values.length ? values[i - 1] : 0);
        }
        return counts;
    }

    private static String scoreRange(String scoreText)
    {
        double score = firstNumber(scoreText);
        if (score < 0)
        {
            return "未填写分数";
        }
        if (score <= 30)
        {
            return "30以内";
        }
        if (score <= 50)
        {
            return "30-50";
        }
        if (score <= 70)
        {
            return "50-70";
        }
        return "70+";
    }

    private static double firstNumber(String text)
    {
        String value = str(text).trim();
        StringBuilder number = new StringBuilder();
        boolean started = false;
        boolean dotUsed = false;
        for (int i = 0; i < value.length(); i++)
        {
            char ch = value.charAt(i);
            if (Character.isDigit(ch))
            {
                number.append(ch);
                started = true;
                continue;
            }
            if (ch == '.' && started && !dotUsed)
            {
                number.append(ch);
                dotUsed = true;
                continue;
            }
            if (started)
            {
                break;
            }
        }
        if (number.length() == 0)
        {
            return -1;
        }
        try
        {
            return Double.parseDouble(number.toString());
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    private static String recentExamScoreForRating(Map<String, Object> rating)
    {
        String score = str(rating.get("recentExamScore")).trim();
        if (score.length() > 0)
        {
            return score;
        }
        return recentExamScoreForUserCourse(str(rating.get("userId")), str(rating.get("courseId")));
    }

    private static String recentExamScoreForUserCourse(Map<String, Object> user, String courseId)
    {
        return recentExamScoreForUserCourse(user == null ? "" : str(user.get("id")), courseId);
    }

    private static String recentExamScoreForUserCourse(String userId, String courseId)
    {
        String score = recentExamScoreFromOrders(userId, courseId, true);
        if (score.length() > 0)
        {
            return score;
        }
        return recentExamScoreFromOrders(userId, courseId, false);
    }

    private static String recentExamScoreFromOrders(String userId, String courseId, boolean requireCourse)
    {
        if (userId.length() == 0)
        {
            return "";
        }
        for (int i = orders.size() - 1; i >= 0; i--)
        {
            Map<String, Object> order = orders.get(i);
            if (!userId.equals(str(order.get("userId"))))
            {
                continue;
            }
            if (requireCourse && courseId.length() > 0 && !courseId.equals(str(order.get("courseId"))))
            {
                continue;
            }
            String score = str(order.get("recentExamScore")).trim();
            if (score.length() > 0)
            {
                return score;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ratingBreakdown(String field)
    {
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> item : lessonRatings)
        {
            String name = str(item.get(field));
            if (name.length() == 0)
            {
                name = "未归类";
            }
            Map<String, Object> bucket = buckets.get(name);
            if (bucket == null)
            {
                bucket = map("name", name, "total", 0, "average", "0.0", "counts", countsMap(new int[]{0, 0, 0, 0, 0}));
                buckets.put(name, bucket);
            }
            int rating = intValue(item.get("rating"));
            if (rating < 1 || rating > 5)
            {
                continue;
            }
            Map<Integer, Integer> counts = (Map<Integer, Integer>) bucket.get("counts");
            counts.put(rating, counts.get(rating) + 1);
            int total = intValue(bucket.get("total")) + 1;
            int weighted = intValue(bucket.get("weighted")) + rating;
            bucket.put("total", total);
            bucket.put("weighted", weighted);
            bucket.put("average", String.format("%.1f", weighted / (float) total));
        }
        List<Map<String, Object>> result = new ArrayList<>(buckets.values());
        for (Map<String, Object> item : result)
        {
            item.remove("weighted");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> courseRatingBreakdown()
    {
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> course : courses)
        {
            if (!"full".equals(str(course.get("kind"))))
            {
                continue;
            }
            String id = str(course.get("id"));
            buckets.put(id, map(
                "id", id,
                "name", stripCourseYear(course.get("courseName")),
                "total", 0,
                "average", "0.0",
                "counts", countsMap(new int[]{0, 0, 0, 0, 0})
            ));
        }
        for (Map<String, Object> item : lessonRatings)
        {
            int rating = intValue(item.get("rating"));
            if (rating < 1 || rating > 5)
            {
                continue;
            }
            String courseId = str(item.get("courseId"));
            String key = courseId.length() > 0 ? courseId : str(item.get("courseTitle"));
            if (key.length() == 0)
            {
                key = "未归类";
            }
            Map<String, Object> bucket = buckets.get(key);
            if (bucket == null)
            {
                bucket = map(
                    "id", key,
                    "name", firstNonBlank(item.get("courseTitle"), resolveCourseTitle(courseId), "未归类"),
                    "total", 0,
                    "average", "0.0",
                    "counts", countsMap(new int[]{0, 0, 0, 0, 0})
                );
                buckets.put(key, bucket);
            }
            addRatingToBucket(bucket, rating);
        }
        List<Map<String, Object>> result = new ArrayList<>(buckets.values());
        for (Map<String, Object> item : result)
        {
            item.remove("weighted");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ratingLessonDetails()
    {
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> item : lessonRatings)
        {
            int rating = intValue(item.get("rating"));
            if (rating < 1 || rating > 5)
            {
                continue;
            }
            String lessonId = str(item.get("lessonId"));
            if (lessonId.length() == 0)
            {
                lessonId = firstNonBlank(item.get("courseId"), item.get("lessonTitle"), "未归类") + "-" + buckets.size();
            }
            Map<String, Object> bucket = buckets.get(lessonId);
            if (bucket == null)
            {
                bucket = map(
                    "id", lessonId,
                    "lessonId", lessonId,
                    "name", firstNonBlank(item.get("lessonTitle"), lessonId),
                    "lessonTitle", firstNonBlank(item.get("lessonTitle"), lessonId),
                    "courseId", item.get("courseId"),
                    "courseTitle", firstNonBlank(item.get("courseTitle"), resolveCourseTitle(item.get("courseId")), "未归类"),
                    "chapterTitle", firstNonBlank(item.get("chapterTitle"), "未归类"),
                    "total", 0,
                    "average", "0.0",
                    "counts", countsMap(new int[]{0, 0, 0, 0, 0}),
                    "scoreCounts", new LinkedHashMap<String, Integer>()
                );
                Map<String, Integer> scoreCounts = (Map<String, Integer>) bucket.get("scoreCounts");
                for (Map<String, Object> group : scoreGroups())
                {
                    scoreCounts.put(str(group.get("range")), 0);
                }
                buckets.put(lessonId, bucket);
            }
            addRatingToBucket(bucket, rating);
            String range = scoreRange(recentExamScoreForRating(item));
            Map<String, Integer> scoreCounts = (Map<String, Integer>) bucket.get("scoreCounts");
            scoreCounts.put(range, scoreCounts.getOrDefault(range, 0) + 1);
        }
        List<Map<String, Object>> result = new ArrayList<>(buckets.values());
        for (Map<String, Object> item : result)
        {
            Map<String, Integer> scoreCounts = (Map<String, Integer>) item.remove("scoreCounts");
            List<Map<String, Object>> ranges = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : scoreCounts.entrySet())
            {
                ranges.add(map("range", entry.getKey(), "count", entry.getValue(), "label", scoreRangeLabel(entry.getKey())));
            }
            item.put("scoreGroups", ranges);
            item.remove("weighted");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void addRatingToBucket(Map<String, Object> bucket, int rating)
    {
        Map<Integer, Integer> counts = (Map<Integer, Integer>) bucket.get("counts");
        counts.put(rating, counts.get(rating) + 1);
        int total = intValue(bucket.get("total")) + 1;
        int weighted = intValue(bucket.get("weighted")) + rating;
        bucket.put("total", total);
        bucket.put("weighted", weighted);
        bucket.put("average", String.format("%.1f", weighted / (float) total));
    }

    private static String scoreRangeLabel(String range)
    {
        if ("30以内".equals(range))
        {
            return "分数<30";
        }
        if ("30-50".equals(range))
        {
            return "30<分数<50";
        }
        if ("50-70".equals(range))
        {
            return "50<分数<70";
        }
        if ("70+".equals(range))
        {
            return "70<分数";
        }
        return range;
    }

    private static Map<String, Object> createOrderRecord(String courseId, Map<String, Object> user, String source)
    {
        return createOrderRecord(courseId, user, source, "");
    }

    private static Map<String, Object> resolveRatingMeta(String lessonId, Map<String, Object> body)
    {
        String courseId = str(body.get("courseId"));
        String chapterTitle = str(body.get("chapterTitle"));
        Map<String, Object> course = findCourse(courseId);
        Map<String, Object> found = course == null || chapterTitle.length() == 0 ? findLessonMeta(lessonId, courseId) : null;
        if (found != null)
        {
            if (course == null)
            {
                course = mapValue(found.get("course"));
            }
            if (courseId.length() == 0)
            {
                courseId = str(found.get("courseId"));
            }
            if (chapterTitle.length() == 0)
            {
                chapterTitle = str(found.get("chapterTitle"));
            }
        }
        return map(
            "courseId", courseId,
            "courseTitle", course == null ? stripCourseYear(body.get("courseTitle")) : stripCourseYear(course.get("courseName")),
            "subjectTitle", course == null ? str(body.get("courseTitle")) : str(course.get("title")),
            "chapterTitle", chapterTitle.length() == 0 ? "未归类章节" : chapterTitle
        );
    }

    private static Map<String, Object> findLessonMeta(String lessonId, String preferredCourseId)
    {
        for (Map<String, Object> course : courses)
        {
            if (preferredCourseId.length() > 0 && !preferredCourseId.equals(course.get("id")))
            {
                continue;
            }
            Map<String, Object> found = findLessonMetaInCourse(lessonId, course);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    private static Map<String, Object> findLessonMetaInCourse(String lessonId, Map<String, Object> course)
    {
        for (Map<String, Object> chapter : mapList(course.get("chapters")))
        {
            if (lessonId.equals(str(chapter.get("title"))))
            {
                return map("course", course, "courseId", course.get("id"), "chapterTitle", chapter.get("title"));
            }
        }
        for (Map<String, Object> version : mapList(course.get("versions")))
        {
            for (Map<String, Object> chapter : mapList(version.get("chapters")))
            {
                for (Map<String, Object> lesson : mapList(chapter.get("items")))
                {
                    if (lessonId.equals(str(lesson.get("title"))))
                    {
                        return map("course", course, "courseId", course.get("id"), "chapterTitle", chapter.get("title"));
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Object> createOrderRecord(String courseId, Map<String, Object> user, String source, String cardCode)
    {
        return createOrderRecord(courseId, user, source, cardCode, expiryByType("year"), "year");
    }

    private static Map<String, Object> createOrderRecord(String courseId, Map<String, Object> user, String source, String cardCode, String expiry, String cardType)
    {
        Map<String, Object> course = findCourse(courseId);
        if (course == null)
        {
            throw new IllegalArgumentException("课程不存在");
        }
        Map<String, Object> order = map(
            "id", "order-" + System.currentTimeMillis(),
            "userId", user == null ? null : user.get("id"),
            "userName", user == null ? "" : user.get("name"),
            "courseId", courseId,
            "courseTitle", stripCourseYear(course.get("courseName")),
            "status", "activated",
            "amount", "0.00",
            "cardCode", cardCode,
            "cardType", cardType,
            "expiresAt", expiry,
            "ownerUserId", ownerUserIdForCard(cardCode),
            "agencyUserId", ownerUserIdForCard(cardCode),
            "source", source,
            "createdAt", now()
        );
        orders.add(order);
        if (user != null && "full".equals(course.get("kind")))
        {
            boolean exists = false;
            for (Map<String, Object> enrollment : enrollments)
            {
                if (user.get("id").equals(enrollment.get("userId")) && courseId.equals(enrollment.get("courseId")) && "active".equals(enrollment.get("status")))
                {
                    exists = true;
                    break;
                }
            }
            if (!exists)
            {
                Map<String, Object> enrollment = map(
                    "id", "enr-" + System.currentTimeMillis(),
                    "userId", user.get("id"),
                    "courseId", courseId,
                    "expiry", expiry,
                    "status", "active",
                    "source", source,
                    "cardCode", cardCode,
                    "cardType", cardType,
                    "orderId", order.get("id")
                );
                enrollments.add(enrollment);
                order.put("enrollmentId", enrollment.get("id"));
            }
        }
        return order;
    }

    private static String normalizeCardCode(Object value)
    {
        return str(value).trim().toLowerCase().replaceAll("\\s+", "");
    }

    private static Map<String, Object> activationCode(String code, String courseId, String cardType, String ownerUserId)
    {
        Map<String, Object> owner = findById(users, ownerUserId);
        Map<String, Object> course = findCourse(courseId);
        return map(
            "id", "card-" + code,
            "code", code,
            "courseId", courseId,
            "courseTitle", course == null ? courseId : stripCourseYear(course.get("courseName")),
            "cardType", normalizeCardType(cardType),
            "cardTypeText", cardTypeText(cardType),
            "durationText", cardDurationText(cardType),
            "ownerUserId", ownerUserId,
            "ownerName", owner == null ? "" : firstNonBlank(owner.get("organizationName"), owner.get("name"), owner.get("id")),
            "status", "available",
            "locked", false,
            "createdAt", now()
        );
    }

    private static String normalizeCardType(Object cardType)
    {
        String type = str(cardType).trim();
        if ("72h".equalsIgnoreCase(type) || "hours72".equalsIgnoreCase(type))
        {
            return "hours72";
        }
        if ("7d".equalsIgnoreCase(type) || "days7".equalsIgnoreCase(type))
        {
            return "days7";
        }
        return "year";
    }

    private static String cardTypeText(Object cardType)
    {
        String type = normalizeCardType(cardType);
        if ("hours72".equals(type))
        {
            return "72小时体验卡";
        }
        if ("days7".equals(type))
        {
            return "7天临时体验卡";
        }
        return "一年期课程卡";
    }

    private static String cardDurationText(Object cardType)
    {
        String type = normalizeCardType(cardType);
        if ("hours72".equals(type))
        {
            return "72小时";
        }
        if ("days7".equals(type))
        {
            return "7天";
        }
        return "一年";
    }

    private static String expiryForCard(Map<String, Object> card)
    {
        return expiryByType(str(card.get("cardType")));
    }

    private static String expiryByType(String cardType)
    {
        String type = normalizeCardType(cardType);
        LocalDateTime now = LocalDateTime.now();
        if ("hours72".equals(type))
        {
            return now.plusHours(72).toString();
        }
        if ("days7".equals(type))
        {
            return now.plusDays(7).toLocalDate().toString();
        }
        return now.plusYears(1).toLocalDate().toString();
    }

    private static Map<String, Object> findActivationCode(String code)
    {
        String normalized = normalizeCardCode(code);
        for (Map<String, Object> card : activationCodes)
        {
            if (normalized.equals(normalizeCardCode(card.get("code"))))
            {
                return card;
            }
        }
        return null;
    }

    private static String ownerUserIdForCard(String cardCode)
    {
        Map<String, Object> card = findActivationCode(cardCode);
        return card == null ? "" : str(card.get("ownerUserId"));
    }

    private static String generateCardCode(String cardType)
    {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        for (int length = 9; length <= 16; length++)
        {
            for (int attempt = 0; attempt < 200; attempt++)
            {
                StringBuilder code = new StringBuilder();
                boolean hasDigit = false;
                boolean hasLetter = false;
                for (int i = 0; i < length; i++)
                {
                    char ch = alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length()));
                    if (Character.isDigit(ch))
                    {
                        hasDigit = true;
                    }
                    else
                    {
                        hasLetter = true;
                    }
                    code.append(ch);
                }
                if (!hasDigit || !hasLetter)
                {
                    continue;
                }
                String value = code.toString();
                if (findActivationCode(value) == null)
                {
                    return value;
                }
            }
        }
        return "a4" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toLowerCase();
    }

    private static boolean isValidGeneratedCardCode(String code)
    {
        return str(code).matches("^(?=.*[0-9])(?=.*[a-z])[0-9a-z]{9,}$");
    }

    private static String generateUserId()
    {
        for (int length = 5; length <= 12; length++)
        {
            long min = (long) Math.pow(10, length - 1);
            long max = (long) Math.pow(10, length) - 1;
            for (int attempt = 0; attempt < 500; attempt++)
            {
                String id = String.valueOf(ThreadLocalRandom.current().nextLong(min, max + 1));
                if (findById(users, id) == null)
                {
                    return id;
                }
            }
        }
        return String.valueOf(System.currentTimeMillis());
    }

    private static void putIfPresent(Map<String, Object> target, Map<String, Object> source, String key)
    {
        if (source.containsKey(key))
        {
            target.put(key, source.get(key));
            if ("cardType".equals(key))
            {
                target.put("cardType", normalizeCardType(source.get(key)));
                target.put("cardTypeText", cardTypeText(source.get(key)));
                target.put("durationText", cardDurationText(source.get(key)));
            }
            if ("ownerUserId".equals(key))
            {
                Map<String, Object> owner = findById(users, str(source.get(key)));
                target.put("ownerName", owner == null ? "" : firstNonBlank(owner.get("organizationName"), owner.get("name"), owner.get("id")));
            }
            if ("courseId".equals(key))
            {
                Map<String, Object> course = findCourse(str(source.get(key)));
                target.put("courseTitle", course == null ? stripCourseYear(source.get(key)) : stripCourseYear(course.get("courseName")));
            }
        }
    }

    private static String cardCourseId(String code)
    {
        if ("GK-MATH-2026".equals(code))
        {
            return "gk-math-full";
        }
        if ("ZK-ENG-2026".equals(code))
        {
            return "zk-yingyu-full";
        }
        return "";
    }

    private static void updateEnrollmentFromOrder(Map<String, Object> order)
    {
        Map<String, Object> enrollment = findById(enrollments, str(order.get("enrollmentId")));
        if (enrollment == null)
        {
            enrollment = findEnrollment(str(order.get("userId")), str(order.get("courseId")));
        }
        if (enrollment == null)
        {
            return;
        }
        copyEnrollmentField(enrollment, order, "studentName");
        copyEnrollmentField(enrollment, order, "gender");
        copyEnrollmentField(enrollment, order, "recentExamScore");
        copyEnrollmentField(enrollment, order, "grade");
        copyEnrollmentField(enrollment, order, "schoolName");
        copyEnrollmentField(enrollment, order, "region");
        copyEnrollmentField(enrollment, order, "cardType");
        copyEnrollmentField(enrollment, order, "cardCode");
        copyEnrollmentField(enrollment, order, "source");
        copyEnrollmentField(enrollment, order, "ownerUserId");
        copyEnrollmentField(enrollment, order, "agencyUserId");
        enrollment.put("expiry", order.get("expiresAt"));
        enrollment.put("orderId", order.get("id"));
        enrollment.put("status", "active");
        order.put("enrollmentId", enrollment.get("id"));
    }

    private static void updateUserCourseExpiry(String userId, String expiresAt)
    {
        if (userId.length() == 0 || expiresAt.length() == 0)
        {
            return;
        }
        Map<String, Object> order = latestOrderForUser(userId);
        if (order != null)
        {
            order.put("expiresAt", expiresAt);
            if ("expired".equals(order.get("status")) && !isExpired(expiresAt))
            {
                order.put("status", "activated");
            }
            Map<String, Object> enrollment = findById(enrollments, str(order.get("enrollmentId")));
            if (enrollment == null)
            {
                enrollment = findEnrollment(userId, str(order.get("courseId")));
            }
            if (enrollment != null)
            {
                enrollment.put("expiry", expiresAt);
                if ("expired".equals(enrollment.get("status")) && !isExpired(expiresAt))
                {
                    enrollment.put("status", "active");
                }
            }
            return;
        }
        Map<String, Object> enrollment = latestAnyEnrollmentForUser(userId);
        if (enrollment != null)
        {
            enrollment.put("expiry", expiresAt);
            if ("expired".equals(enrollment.get("status")) && !isExpired(expiresAt))
            {
                enrollment.put("status", "active");
            }
        }
    }

    private static void copyEnrollmentField(Map<String, Object> enrollment, Map<String, Object> order, String key)
    {
        if (order.containsKey(key))
        {
            enrollment.put(key, order.get(key));
        }
    }

    private static void closeEnrollmentForOrder(Map<String, Object> order)
    {
        Map<String, Object> enrollment = findById(enrollments, str(order.get("enrollmentId")));
        if (enrollment == null)
        {
            enrollment = findEnrollment(str(order.get("userId")), str(order.get("courseId")));
        }
        if (enrollment != null)
        {
            enrollment.put("status", "closed");
            enrollment.put("closedAt", now());
        }
    }

    private static Map<String, Object> findEnrollment(Map<String, Object> user, String courseId)
    {
        return user == null ? null : findEnrollment(str(user.get("id")), courseId);
    }

    private static Map<String, Object> findEnrollment(String userId, String courseId)
    {
        if (userId.length() == 0 || courseId.length() == 0)
        {
            return null;
        }
        for (int i = enrollments.size() - 1; i >= 0; i--)
        {
            Map<String, Object> enrollment = enrollments.get(i);
            if (userId.equals(str(enrollment.get("userId"))) && courseId.equals(str(enrollment.get("courseId"))))
            {
                return enrollment;
            }
        }
        return null;
    }

    private static boolean hasActiveEnrollment(Map<String, Object> user, String courseId)
    {
        Map<String, Object> enrollment = findEnrollment(user, courseId);
        return enrollment != null && isEnrollmentOpen(enrollment);
    }

    private static boolean isEnrollmentOpen(Map<String, Object> enrollment)
    {
        if (!"active".equals(enrollment.get("status")) || isExpired(str(enrollment.get("expiry"))))
        {
            return false;
        }
        Map<String, Object> card = findActivationCode(str(enrollment.get("cardCode")));
        return card == null || !activationCardLocked(card);
    }

    private static void refreshExpiredEnrollments()
    {
        boolean changed = false;
        for (Map<String, Object> enrollment : enrollments)
        {
            if ("active".equals(enrollment.get("status")) && isExpired(str(enrollment.get("expiry"))))
            {
                enrollment.put("status", "expired");
                enrollment.put("expiredAt", now());
                changed = true;
            }
        }
        for (Map<String, Object> order : orders)
        {
            if ("activated".equals(order.get("status")) && isExpired(str(order.get("expiresAt"))))
            {
                order.put("status", "expired");
                order.put("expiredAt", now());
                changed = true;
            }
        }
        if (changed)
        {
            // Avoid persisting from class initialization paths; runtime callers will save through the controller instance.
        }
    }

    private static boolean isExpired(String expiry)
    {
        if (expiry.length() == 0)
        {
            return false;
        }
        try
        {
            if (expiry.length() <= 10)
            {
                return LocalDate.parse(expiry.substring(0, 10)).isBefore(LocalDate.now());
            }
            return LocalDateTime.parse(expiry).isBefore(LocalDateTime.now());
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static Map<String, Object> currentUser(HttpServletRequest request)
    {
        String token = request.getHeader("Authorization");
        if (token == null)
        {
            return null;
        }
        token = token.replace("Bearer ", "");
        if (!token.startsWith("local-"))
        {
            return null;
        }
        return findById(users, token.substring(6));
    }

    private static Map<String, Object> publicUser(Map<String, Object> user)
    {
        Map<String, Object> item = new LinkedHashMap<>(user);
        item.remove("password");
        String userId = str(user.get("id"));
        Map<String, Object> latestOrder = latestOpenOrderForUser(userId);
        Map<String, Object> latestEnrollment = latestEnrollmentForUser(userId);
        List<Map<String, Object>> activatedCourses = activatedCoursesForUser(userId);
        List<String> names = new ArrayList<>();
        for (Map<String, Object> course : activatedCourses)
        {
            String name = str(course.get("courseTitle"));
            if (name.length() > 0)
            {
                names.add(name);
            }
        }
        Map<String, Object> latest = latestOrder != null ? latestOrder : latestEnrollment;
        item.put("activatedCourses", activatedCourses);
        item.put("openCourseCount", activatedCourses.size());
        item.put("openCourseNames", String.join("、", names));
        item.put("openedAt", latestOrder == null ? "" : latestOrder.get("createdAt"));
        item.put("openedCardCode", firstNonBlank(latestOrder == null ? "" : latestOrder.get("cardCode"), latestEnrollment == null ? "" : latestEnrollment.get("cardCode")));
        item.put("expiresAt", firstNonBlank(latestOrder == null ? "" : latestOrder.get("expiresAt"), latestEnrollment == null ? "" : latestEnrollment.get("expiry")));
        item.put("studentName", firstNonBlank(latest == null ? "" : latest.get("studentName"), user.get("studentName"), user.get("name")));
        item.put("gender", firstNonBlank(latest == null ? "" : latest.get("gender"), user.get("gender")));
        item.put("recentExamScore", firstNonBlank(latest == null ? "" : latest.get("recentExamScore"), user.get("recentExamScore")));
        item.put("grade", firstNonBlank(latest == null ? "" : latest.get("grade"), user.get("grade")));
        item.put("schoolName", firstNonBlank(latest == null ? "" : latest.get("schoolName"), user.get("schoolName")));
        item.put("region", firstNonBlank(latest == null ? "" : latest.get("region"), user.get("region")));
        item.put("studentStats", studentLearningSnapshot(userId));
        item.put("accountTypeText", accountTypeTextForUser(user, activatedCourses.size()));
        return item;
    }

    private static String accountTypeTextForUser(Map<String, Object> user, int openCourseCount)
    {
        String role = str(user.get("role"));
        if ("agency_admin".equals(role))
        {
            return "分机构账号";
        }
        if ("admin".equals(role))
        {
            return "管理员";
        }
        return openCourseCount > 0 ? "正式学员" : "试用学员";
    }

    private static Map<String, Object> latestOpenOrderForUser(String userId)
    {
        for (int i = orders.size() - 1; i >= 0; i--)
        {
            Map<String, Object> order = orders.get(i);
            if (userId.equals(str(order.get("userId"))) && "activated".equals(order.get("status")) && !isExpired(str(order.get("expiresAt"))))
            {
                return order;
            }
        }
        return null;
    }

    private static Map<String, Object> latestOrderForUser(String userId)
    {
        for (int i = orders.size() - 1; i >= 0; i--)
        {
            Map<String, Object> order = orders.get(i);
            if (userId.equals(str(order.get("userId"))))
            {
                return order;
            }
        }
        return null;
    }

    private static Map<String, Object> latestOrderForCard(String cardCode)
    {
        String code = normalizeCardCode(cardCode);
        for (int i = orders.size() - 1; i >= 0; i--)
        {
            Map<String, Object> order = orders.get(i);
            if (code.equals(normalizeCardCode(order.get("cardCode"))))
            {
                return order;
            }
        }
        return null;
    }

    private static Map<String, Object> latestEnrollmentForUser(String userId)
    {
        for (int i = enrollments.size() - 1; i >= 0; i--)
        {
            Map<String, Object> enrollment = enrollments.get(i);
            if (userId.equals(str(enrollment.get("userId"))) && isEnrollmentOpen(enrollment))
            {
                return enrollment;
            }
        }
        return null;
    }

    private static Map<String, Object> latestAnyEnrollmentForUser(String userId)
    {
        for (int i = enrollments.size() - 1; i >= 0; i--)
        {
            Map<String, Object> enrollment = enrollments.get(i);
            if (userId.equals(str(enrollment.get("userId"))))
            {
                return enrollment;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> activatedCoursesForUser(String userId)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> enrollment : enrollments)
        {
            if (!userId.equals(str(enrollment.get("userId"))) || !isEnrollmentOpen(enrollment))
            {
                continue;
            }
            String courseId = str(enrollment.get("courseId"));
            Map<String, Object> course = findCourse(courseId);
            int progress = averageProgressForCourse(userId, courseId);
            result.add(map(
                "courseId", courseId,
                "courseTitle", course == null ? courseId : stripCourseYear(course.get("courseName")),
                "expiresAt", enrollment.get("expiry"),
                "progress", progress > 0 ? progress + "%" : "暂无",
                "attempts", attemptCountForCourse(userId, courseId),
                "averageScore", averageScoreForCourse(userId, courseId)
            ));
        }
        return result;
    }

    private static int averageProgressForUser(String userId)
    {
        int total = 0;
        int count = 0;
        for (Map<String, Object> progress : lessonProgress.values())
        {
            if (userId.equals(str(progress.get("userId"))))
            {
                total += intValue(progress.get("percent"));
                count++;
            }
        }
        return count == 0 ? 0 : Math.round(total / (float) count);
    }

    private static int averageProgressForCourse(String userId, String courseId)
    {
        int total = 0;
        int count = 0;
        for (Map<String, Object> progress : lessonProgress.values())
        {
            if (userId.equals(str(progress.get("userId"))) && courseId.equals(str(progress.get("courseId"))))
            {
                total += intValue(progress.get("percent"));
                count++;
            }
        }
        return count == 0 ? 0 : Math.round(total / (float) count);
    }

    private static int attemptCountForCourse(String userId, String courseId)
    {
        int count = 0;
        for (Map<String, Object> attempt : attempts)
        {
            if (userId.equals(str(attempt.get("userId"))) && (courseId.length() == 0 || courseId.equals(str(attempt.get("courseId")))))
            {
                count++;
            }
        }
        return count;
    }

    private static int averageScoreForCourse(String userId, String courseId)
    {
        int total = 0;
        int count = 0;
        for (Map<String, Object> attempt : attempts)
        {
            if (userId.equals(str(attempt.get("userId"))) && (courseId.length() == 0 || courseId.equals(str(attempt.get("courseId")))))
            {
                total += intValue(attempt.get("score"));
                count++;
            }
        }
        return count == 0 ? 0 : Math.round(total / (float) count);
    }

    private static String firstNonBlank(Object... values)
    {
        for (Object value : values)
        {
            String text = str(value).trim();
            if (text.length() > 0)
            {
                return text;
            }
        }
        return "";
    }

    private static Map<String, Object> findCourse(String id)
    {
        return findById(courses, id);
    }

    private static Map<String, Object> findById(List<Map<String, Object>> list, String id)
    {
        for (Map<String, Object> item : list)
        {
            if (id.equals(str(item.get("id"))))
            {
                return item;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> filterByUser(List<Map<String, Object>> list, Map<String, Object> user)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : list)
        {
            if (user == null || sameUser(item, user))
            {
                result.add(item);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> filterByCourse(List<Map<String, Object>> list, String courseId)
    {
        String expected = str(courseId).trim();
        if (expected.length() == 0)
        {
            return list;
        }
        expected = scopedCourseId(expected);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : list)
        {
            String itemCourseId = scopedCourseId(item.get("courseId"));
            if (expected.equals(itemCourseId))
            {
                result.add(item);
            }
        }
        return result;
    }

    private static String scopedCourseId(Object courseId)
    {
        String value = str(courseId).trim();
        return value.length() == 0 ? "gk-math-full" : value;
    }

    private static boolean ensureWrongCourseMeta(Map<String, Object> wrong)
    {
        boolean changed = false;
        String courseId = scopedCourseId(wrong.get("courseId"));
        if (!courseId.equals(str(wrong.get("courseId"))))
        {
            wrong.put("courseId", courseId);
            changed = true;
        }
        if (str(wrong.get("courseTitle")).length() == 0)
        {
            wrong.put("courseTitle", resolveCourseTitle(courseId));
            changed = true;
        }
        if (str(wrong.get("subjectTitle")).length() == 0)
        {
            wrong.put("subjectTitle", resolveSubjectTitle(courseId));
            changed = true;
        }
        return changed;
    }

    private static boolean sameUser(Map<String, Object> item, Map<String, Object> user)
    {
        Object userId = user == null ? null : user.get("id");
        Object itemUserId = item.get("userId");
        return userId == null ? itemUserId == null : userId.equals(itemUserId);
    }

    private static String progressKey(Map<String, Object> user, String lessonId)
    {
        return (user == null ? "guest" : str(user.get("id"))) + ":" + lessonId;
    }

    private static int findRating(String lessonId, Map<String, Object> user)
    {
        for (int i = lessonRatings.size() - 1; i >= 0; i--)
        {
            Map<String, Object> item = lessonRatings.get(i);
            if (lessonId.equals(item.get("lessonId")) && sameUser(item, user))
            {
                return intValue(item.get("rating"));
            }
        }
        return 0;
    }

    private static int countStatus(List<Map<String, Object>> list, String status)
    {
        int total = 0;
        for (Map<String, Object> item : list)
        {
            if (status.equals(item.get("status")))
            {
                total++;
            }
        }
        return total;
    }

    private static List<Map<String, Object>> tail(List<Map<String, Object>> list, int size)
    {
        int start = Math.max(0, list.size() - size);
        List<Map<String, Object>> result = new ArrayList<>(list.subList(start, list.size()));
        Collections.reverse(result);
        return result;
    }

    private static Map<String, Object> plate(String name, int score)
    {
        return map("name", name, "score", score, "level", score >= 85 ? map("label", "优秀", "color", "purple") : score >= 75 ? map("label", "良好", "color", "green") : score >= 60 ? map("label", "中等", "color", "yellow") : map("label", "薄弱", "color", "red"));
    }

    private static void ensureCourseDefaults(Map<String, Object> course)
    {
        if (!course.containsKey("status"))
        {
            course.put("status", "published");
        }
        if (!course.containsKey("sort"))
        {
            course.put("sort", courses.size() + 1);
        }
        if (!course.containsKey("openMode"))
        {
            course.put("openMode", "trial".equals(course.get("kind")) ? "trial" : "card");
        }
        if (!course.containsKey("openText"))
        {
            course.put("openText", "trial".equals(course.get("kind")) ? "试听免费" : "激活课程");
        }
        if (!course.containsKey("versions"))
        {
            course.put("versions", list(map("name", "2026版", "chapters", new ArrayList<Object>())));
        }
        List<Map<String, Object>> versions = mapList(course.get("versions"));
        while (versions.size() < 3)
        {
            String name = versions.size() == 1 ? "绝招课" : "知识巩固";
            versions.add(map("name", name, "chapters", new ArrayList<Object>()));
        }
        course.put("versions", versions);
        if (!course.containsKey("chapters"))
        {
            course.put("chapters", new ArrayList<Object>());
        }
        if ("trial".equals(course.get("kind")) && countUploadedCourseLessons(course) == 0)
        {
            String title = str(course.get("title"));
            if (title.length() == 0)
            {
                title = str(course.get("full"));
            }
            List<Map<String, Object>> trialChapters = list(
                trialChapter(title + "导学试听", 5),
                trialChapter(title + "核心技巧试听", 5),
                trialChapter(title + "真题讲练试听", 5)
            );
            course.put("versions", list(map("name", "2026版", "chapters", trialChapters)));
            course.put("chapters", trialChapters);
        }
        if (!course.containsKey("quizzes"))
        {
            course.put("quizzes", list(makeQuiz("入门测", "未学习", "去测评")));
        }
        if (!course.containsKey("knowledgeQuizzes"))
        {
            course.put("knowledgeQuizzes", new ArrayList<Object>());
        }
        int lessonCount = countUploadedCourseLessons(course);
        if (lessonCount > 0)
        {
            course.put("totalLessons", lessonCount);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value)
    {
        if (value instanceof Map)
        {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List)
        {
            for (Object item : (List<Object>) value)
            {
                if (item instanceof Map)
                {
                    result.add((Map<String, Object>) item);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value)
    {
        if (value instanceof List)
        {
            List<String> result = new ArrayList<>();
            for (Object item : (List<Object>) value)
            {
                result.add(str(item));
            }
            return result;
        }
        return new ArrayList<>();
    }

    private static List<String> commaList(String value)
    {
        List<String> result = new ArrayList<>();
        for (String item : str(value).split(","))
        {
            String text = item.trim();
            if (text.length() > 0)
            {
                result.add(text);
            }
        }
        return result;
    }

    private static String str(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private static String valueOrDefault(Object value, Object fallback)
    {
        String text = str(value).trim();
        return text.length() == 0 ? str(fallback) : text;
    }

    private static String stripCourseYear(Object value)
    {
        return str(value)
            .replaceAll("((中考|高考)(语文|数学|英语|物理|化学|生物|历史|政治|地理))20\\d{2}", "$1")
            .replaceAll("(《[^》]*?)20\\d{2}(》)", "$1$2")
            .replaceAll("(《[^》]+》)\\s*20\\d{2}", "$1")
            .trim();
    }

    private static int intValue(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        try
        {
            return Integer.parseInt(str(value));
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private static boolean boolValue(Object value, boolean fallback)
    {
        if (value instanceof Boolean)
        {
            return (Boolean) value;
        }
        String text = str(value).trim().toLowerCase();
        if (text.length() == 0)
        {
            return fallback;
        }
        return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "enabled".equals(text);
    }

    private static double doubleValue(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        try
        {
            return Double.parseDouble(str(value));
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    @SafeVarargs
    private static <T> List<T> list(T... items)
    {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static Map<String, Object> map(Object... pairs)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static String now()
    {
        return java.time.LocalDateTime.now().toString();
    }
}
