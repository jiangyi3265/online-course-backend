package com.ruoyi.web.controller.course;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
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
import com.ruoyi.common.core.domain.AjaxResult;

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
    private static final List<Map<String, Object>> attempts = new ArrayList<>();
    private static final List<Map<String, Object>> wrongQuestions = new ArrayList<>();
    private static final List<Map<String, Object>> lessonRatings = new ArrayList<>();
    private static final List<Map<String, Object>> aiChats = new ArrayList<>();
    private static final Map<String, Map<String, Object>> lessonProgress = new ConcurrentHashMap<>();

    @Value("${ruoyi.profile:}")
    private String profilePath;

    static
    {
        initUsers();
        initCourses();
        initDocs();
        initQuestions();
        initStudyData();
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
                restoreList(data, "attempts", attempts);
                restoreList(data, "wrongQuestions", wrongQuestions);
                restoreList(data, "lessonRatings", lessonRatings);
                restoreList(data, "aiChats", aiChats);
                restoreProgress(data);
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
        if (!phone.matches("^1\\d{10}$"))
        {
            return AjaxResult.error("请输入正确的手机号");
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
                "id", "u" + System.currentTimeMillis(),
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
    public AjaxResult appCourse(@PathVariable String id)
    {
        Map<String, Object> course = findCourse(id);
        return course == null ? AjaxResult.error("课程不存在") : AjaxResult.success(course);
    }

    @GetMapping("/app/my/courses")
    public AjaxResult myCourses(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        if (user == null)
        {
            return AjaxResult.success(Collections.emptyList());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> enrollment : enrollments)
        {
            if (!str(user.get("id")).equals(enrollment.get("userId")) || !"active".equals(enrollment.get("status")))
            {
                continue;
            }
            Map<String, Object> course = findCourse(str(enrollment.get("courseId")));
            if (course != null)
            {
                list.add(map(
                    "id", course.get("id"),
                    "title", course.get("courseName"),
                    "courseName", course.get("courseName"),
                    "sub", course.get("introduction"),
                    "expiry", enrollment.get("expiry"),
                    "cover", course.get("cover"),
                    "subject", course.get("subject"),
                    "kind", course.get("kind")
                ));
            }
        }
        return AjaxResult.success(list);
    }

    @GetMapping("/app/my/docs")
    public AjaxResult myDocs(@RequestParam(required = false, defaultValue = "") String kw)
    {
        String text = kw.trim().toLowerCase();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> doc : docs)
        {
            if (Boolean.FALSE.equals(doc.get("visible")))
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
    public AjaxResult studyReport(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        List<Map<String, Object>> userAttempts = filterByUser(attempts, user);
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
        List<Map<String, Object>> overview = list(
            map("label", "练习次数", "value", userAttempts.size() + "次"),
            map("label", "平均得分", "value", avg + "分"),
            map("label", "待复盘错题", "value", wrongCount + "道"),
            map("label", "本周建议", "value", avg >= 80 ? "保持节奏" : "优先补弱")
        );
        List<String> suggestions = wrongCount > 0
            ? Arrays.asList("先复盘错题与测试，再进入知识巩固。", "每次练习后查看解析，记录易错概念。")
            : Collections.singletonList("当前错题较少，可以继续推进新章节。");
        Collections.reverse(userAttempts);
        return AjaxResult.success(map(
            "summary", getStudySummary(),
            "overview", overview,
            "attempts", userAttempts.size() > 8 ? userAttempts.subList(0, 8) : userAttempts,
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
    public AjaxResult lessonVideo(@RequestParam String lessonId, HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        Map<String, Object> data = map(
            "id", lessonId,
            "title", lessonId,
            "videoUrl", SAMPLE_VIDEO_URL,
            "poster", "/static/courses/gk-shuxue-full-detail.jpg",
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
        if (existing == null)
        {
            existing = map("id", "rating-" + System.currentTimeMillis());
            lessonRatings.add(existing);
        }
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
    public AjaxResult practice(@RequestParam String title)
    {
        return AjaxResult.success(map("title", title, "type", "practice", "questions", publicQuestions(questionsFor(title))));
    }

    @PostMapping("/app/practice/submit")
    public AjaxResult submitPractice(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Map<String, Object> result = grade(body, currentUser(request));
        persistData();
        return AjaxResult.success(result);
    }

    @GetMapping("/app/quiz")
    public AjaxResult quiz(@RequestParam String quizId)
    {
        return AjaxResult.success(map("id", quizId, "title", quizId, "type", "quiz", "questions", publicQuestions(questionsFor(quizId))));
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
    public AjaxResult wrongbook(HttpServletRequest request)
    {
        Map<String, Object> user = currentUser(request);
        List<Map<String, Object>> list = filterByUser(wrongQuestions, user);
        list.sort((a, b) -> str(b.get("updatedAt")).compareTo(str(a.get("updatedAt"))));
        return AjaxResult.success(list);
    }

    @PostMapping("/app/wrongbook/mastered")
    public AjaxResult markWrong(@RequestBody Map<String, Object> body)
    {
        String id = str(body.get("id"));
        for (Map<String, Object> wrong : wrongQuestions)
        {
            if (id.equals(wrong.get("id")))
            {
                wrong.put("mastered", true);
                wrong.put("updatedAt", now());
                persistData();
                return AjaxResult.success(wrong);
            }
        }
        return AjaxResult.success(new LinkedHashMap<String, Object>());
    }

    @GetMapping("/app/reinforce")
    public AjaxResult reinforce(@RequestParam(required = false, defaultValue = "gk-math-full") String courseId)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> item : reinforcePoints)
        {
            if (courseId.equals(item.get("courseId")))
            {
                list.add(item);
            }
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
        String recentExamScore = str(body.get("recentExamScore")).trim();
        String grade = str(body.get("grade")).trim();
        String schoolName = str(body.get("schoolName")).trim();
        String region = str(body.get("region")).trim();
        if (code.length() == 0 || studentName.length() == 0 || recentExamScore.length() == 0 || grade.length() == 0 || schoolName.length() == 0 || region.length() == 0)
        {
            return AjaxResult.error("请填写激活码、学生名字、科目最近考试分数、年级、学校名字和所在地区");
        }
        String courseId = cardCourseId(code);
        if (courseId.length() == 0)
        {
            return AjaxResult.error("激活码无效");
        }
        Map<String, Object> order = createOrderRecord(courseId, currentUser(request), "激活码开通", code);
        order.put("studentName", studentName);
        order.put("recentExamScore", recentExamScore);
        order.put("grade", grade);
        order.put("schoolName", schoolName);
        order.put("region", region);
        persistData();
        return AjaxResult.success(order);
    }

    @GetMapping("/admin/dashboard")
    public AjaxResult dashboard()
    {
        return AjaxResult.success(map(
            "courseTotal", courses.size(),
            "userTotal", users.size(),
            "orderTotal", orders.size(),
            "authPending", countStatus(authRequests, "pending"),
            "wrongTotal", wrongQuestions.size(),
            "attemptTotal", attempts.size(),
            "ratingStats", ratingStats(),
            "recentOrders", tail(orders, 6),
            "recentAuthRequests", tail(authRequests, 6)
        ));
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
        persistData();
        return AjaxResult.success(course);
    }

    @DeleteMapping("/admin/courses/{id}")
    public AjaxResult deleteCourse(@PathVariable String id)
    {
        courses.removeIf(item -> id.equals(item.get("id")));
        persistData();
        return AjaxResult.success();
    }

    @GetMapping("/admin/docs")
    public AjaxResult adminDocs()
    {
        return AjaxResult.success(docs);
    }

    @PostMapping("/admin/docs")
    public AjaxResult addDoc(@RequestBody Map<String, Object> doc)
    {
        if (str(doc.get("id")).length() == 0)
        {
            doc.put("id", "doc-" + System.currentTimeMillis());
        }
        docs.add(doc);
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
        persistData();
        return AjaxResult.success(doc);
    }

    @DeleteMapping("/admin/docs/{id}")
    public AjaxResult deleteDoc(@PathVariable String id)
    {
        docs.removeIf(item -> id.equals(item.get("id")));
        persistData();
        return AjaxResult.success();
    }

    @GetMapping("/admin/questions")
    public AjaxResult adminQuestions()
    {
        return AjaxResult.success(questions);
    }

    @PostMapping("/admin/questions")
    public AjaxResult addQuestion(@RequestBody Map<String, Object> question)
    {
        if (str(question.get("id")).length() == 0)
        {
            question.put("id", "q-" + System.currentTimeMillis());
        }
        questions.add(question);
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
        question.putAll(body);
        question.put("id", id);
        persistData();
        return AjaxResult.success(question);
    }

    @DeleteMapping("/admin/questions/{id}")
    public AjaxResult deleteQuestion(@PathVariable String id)
    {
        questions.removeIf(item -> id.equals(item.get("id")));
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
        persistData();
        return AjaxResult.success(request);
    }

    @GetMapping("/admin/orders")
    public AjaxResult adminOrders()
    {
        return AjaxResult.success(orders);
    }

    @PostMapping("/admin/orders")
    public AjaxResult adminCreateOrder(@RequestBody Map<String, Object> body)
    {
        Map<String, Object> user = findById(users, str(body.get("userId")));
        Map<String, Object> order = createOrderRecord(str(body.get("courseId")), user, "后台开课", str(body.get("cardCode")));
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
        data.put("attempts", copyList(attempts));
        data.put("wrongQuestions", copyList(wrongQuestions));
        data.put("lessonRatings", copyList(lessonRatings));
        data.put("aiChats", copyList(aiChats));
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
        users.add(map("phone", "13800138000", "password", "123456", "name", "张同学", "id", "56596", "tenantId", 52, "role", "student", "status", "active"));
        users.add(map("phone", "13900139000", "password", "123456", "name", "李同学", "id", "56597", "tenantId", 52, "role", "student", "status", "active"));
        users.add(map("phone", "18888888888", "password", "888888", "name", "王老师", "id", "10001", "tenantId", 52, "role", "teacher", "status", "active"));
    }

    private static void initCourses()
    {
        courses.add(simpleCourse("zk-yuwen-trial", "zhongkao", "trial", "中考语文2026", "/static/courses/zk-yuwen.jpg", 1086, 1));
        courses.add(simpleCourse("zk-shuxue-trial", "zhongkao", "trial", "中考数学2026", "/static/courses/zk-shuxue.jpg", 1456, 2));
        courses.add(simpleCourse("zk-yingyu-trial", "zhongkao", "trial", "中考英语2026", "/static/courses/zk-yingyu.jpg", 1289, 3));
        courses.add(simpleCourse("zk-wuli-trial", "zhongkao", "trial", "中考物理2026", "/static/courses/zk-wuli.jpg", 1176, 4));
        courses.add(simpleCourse("zk-huaxue-trial", "zhongkao", "trial", "中考化学2026", "/static/courses/zk-huaxue.jpg", 1237, 5));
        courses.add(simpleCourse("gk-yuwen-trial", "gaokao", "trial", "高考语文2026", "/static/courses/gk-yuwen.jpg", 1078, 6));
        courses.add(mathTrial());
        courses.add(simpleCourse("gk-yingyu-trial", "gaokao", "trial", "高考英语2026", "/static/courses/gk-yingyu.jpg", 1360, 8));
        courses.add(simpleCourse("gk-wuli-trial", "gaokao", "trial", "高考物理2026", "/static/courses/gk-wuli.jpg", 1121, 9));
        courses.add(simpleCourse("gk-huaxue-trial", "gaokao", "trial", "高考化学2026", "/static/courses/gk-huaxue.jpg", 980, 10));
        courses.add(simpleCourse("zk-yuwen-full", "zhongkao", "full", "中考语文2026", "/static/courses/zk-yuwen.jpg", 438, 11));
        courses.add(simpleCourse("zk-shuxue-full", "zhongkao", "full", "中考数学2026", "/static/courses/zk-shuxue.jpg", 521, 12));
        courses.add(simpleCourse("zk-yingyu-full", "zhongkao", "full", "中考英语2026", "/static/courses/zk-yingyu.jpg", 487, 13));
        courses.add(simpleCourse("zk-wuli-full", "zhongkao", "full", "中考物理2026", "/static/courses/zk-wuli.jpg", 366, 14));
        courses.add(simpleCourse("zk-huaxue-full", "zhongkao", "full", "中考化学2026", "/static/courses/zk-huaxue.jpg", 342, 15));
        courses.add(simpleCourse("gk-yuwen-full", "gaokao", "full", "高考语文2026", "/static/courses/gk-yuwen.jpg", 406, 16));
        courses.add(mathFull());
        courses.add(simpleCourse("gk-yingyu-full", "gaokao", "full", "高考英语2026", "/static/courses/gk-yingyu-full.jpg", 512, 18));
        courses.add(simpleCourse("gk-wuli-full", "gaokao", "full", "高考物理2026", "/static/courses/gk-wuli-full.jpg", 389, 19));
        courses.add(simpleCourse("gk-huaxue-full", "gaokao", "full", "高考化学2026", "/static/courses/gk-huaxue.jpg", 318, 20));

        enrollments.add(map("id", "enr-1", "userId", "56596", "courseId", "zk-yingyu-full", "expiry", "2026-02-15", "status", "active", "source", "授权"));
        enrollments.add(map("id", "enr-2", "userId", "56596", "courseId", "zk-shuxue-full", "expiry", "2026-02-14", "status", "active", "source", "授权"));
        enrollments.add(map("id", "enr-3", "userId", "56596", "courseId", "gk-math-full", "expiry", "2026-05-07", "status", "active", "source", "授权"));
        enrollments.add(map("id", "enr-4", "userId", "56596", "courseId", "gk-yingyu-full", "expiry", "2026-01-27", "status", "active", "source", "授权"));
        enrollments.add(map("id", "enr-5", "userId", "56596", "courseId", "gk-wuli-full", "expiry", "2026-02-05", "status", "active", "source", "授权"));
    }

    private static void initDocs()
    {
        docs.add(map("id", "doc-1", "courseId", "gk-math-full", "title", "高考数学集合逻辑讲义.pdf", "fileUrl", "#", "fileType", "PDF", "size", "1.2MB", "visible", true));
        docs.add(map("id", "doc-2", "courseId", "gk-math-full", "title", "导数极值专题学案.pdf", "fileUrl", "#", "fileType", "PDF", "size", "2.4MB", "visible", true));
        docs.add(map("id", "doc-3", "courseId", "zk-yingyu-full", "title", "中考英语核心词汇表.xlsx", "fileUrl", "#", "fileType", "XLSX", "size", "640KB", "visible", true));
    }

    private static void initQuestions()
    {
        questions.add(map("id", "q-logic-1", "stem", "已知集合 A={x|x>1}，B={x|x<4}，则 A∩B 为", "options", Arrays.asList("x>1", "x<4", "1<x<4", "空集"), "answer", 2, "analysis", "交集要同时满足两个条件，所以是 1<x<4。", "knowledge", "集合交集"));
        questions.add(map("id", "q-logic-2", "stem", "命题“若 p 则 q”的逆否命题是", "options", Arrays.asList("若 q 则 p", "若非 p 则非 q", "若非 q 则非 p", "p 且 q"), "answer", 2, "analysis", "原命题与逆否命题等价，逆否为若非 q 则非 p。", "knowledge", "充分必要条件"));
        questions.add(map("id", "q-derivative-1", "stem", "函数 f(x)=x^2 在 x=2 处的导数为", "options", Arrays.asList("2", "3", "4", "5"), "answer", 2, "analysis", "f'(x)=2x，代入 x=2 得 4。", "knowledge", "导数计算"));
        questions.add(map("id", "q-series-1", "stem", "等差数列首项为 2，公差为 3，则第 5 项为", "options", Arrays.asList("11", "12", "13", "14"), "answer", 3, "analysis", "a5=a1+4d=2+12=14。", "knowledge", "等差数列"));
    }

    private static void initStudyData()
    {
        reinforcePoints.add(map("id", "kp-logic", "courseId", "gk-math-full", "title", "集合与逻辑", "mastery", 68, "status", "待巩固", "questionIds", Arrays.asList("q-logic-1", "q-logic-2")));
        reinforcePoints.add(map("id", "kp-derivative", "courseId", "gk-math-full", "title", "导数基础", "mastery", 74, "status", "学习中", "questionIds", Arrays.asList("q-derivative-1")));
        reinforcePoints.add(map("id", "kp-series", "courseId", "gk-math-full", "title", "数列通项", "mastery", 58, "status", "薄弱", "questionIds", Arrays.asList("q-series-1")));
        studyPlans.add(map("courseId", "gk-math-full", "title", "高考数学阶段学案", "tasks", list(
            map("id", "plan-1", "title", "复习集合交集与补集", "type", "知识回顾", "done", true),
            map("id", "plan-2", "title", "完成导数基础 3 道巩固题", "type", "训练任务", "done", false),
            map("id", "plan-3", "title", "整理错题与测试中的数列题", "type", "错题复盘", "done", false)
        )));
    }

    private static Map<String, Object> mathTrial()
    {
        Map<String, Object> course = simpleCourse("gk-math-trial", "gaokao", "trial", "高考数学2026", "/static/courses/gk-shuxue.jpg", 1450, 7);
        course.put("subject", "gaokao-math");
        course.put("title", "高考数学");
        course.put("courseName", "《高考数学2026》试听课");
        course.put("introduction", "《高考数学2026》试听课");
        course.put("detailCover", "/static/courses/gk-shuxue-trial-detail.jpg");
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
        Map<String, Object> course = simpleCourse("gk-math-full", "gaokao", "full", "高考数学2026", "/static/courses/gk-shuxue-full.jpg", 117, 17);
        course.put("subject", "gaokao-math");
        course.put("title", "高考数学");
        course.put("courseName", "《高考数学2026》");
        course.put("introduction", "《高考数学》2026");
        course.put("detailCover", "/static/courses/gk-shuxue-full-detail.jpg");
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
        return map(
            "id", id,
            "stage", stage,
            "kind", kind,
            "subject", id.replaceAll("-(trial|full)$", ""),
            "full", full,
            "title", full.replace("2026", ""),
            "courseName", "trial".equals(kind) ? "《" + full + "》试听课" : "《" + full.replace("2026", "") + "》2026",
            "introduction", "trial".equals(kind) ? "《" + full + "》试听课" : "《" + full.replace("2026", "") + "》2026",
            "cover", cover,
            "detailCover", cover,
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
            "versions", list(map("name", "2026版", "chapters", new ArrayList<Object>())),
            "chapters", new ArrayList<Object>(),
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
            "full", course.get("full"),
            "title", course.get("title"),
            "suffix", "trial".equals(course.get("kind")) ? "试听课" : "",
            "sub", course.get("courseName"),
            "openMode", course.get("openMode"),
            "openText", course.get("openText"),
            "cover", course.get("cover"),
            "isTry", "trial".equals(course.get("kind")),
            "studyCount", course.get("studyCount"),
            "status", course.get("status"),
            "sort", course.get("sort")
        );
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
        Map<String, Object> answers = mapValue(payload.get("answers"));
        List<Map<String, Object>> source = "reinforce".equals(type) ? questionsByIds(stringList(payload.get("questionIds"))) : questionsFor(title);
        int correct = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> q : source)
        {
            int selected = intValue(answers.get(q.get("id")));
            boolean ok = selected == intValue(q.get("answer"));
            if (ok)
            {
                correct++;
            }
            else
            {
                Map<String, Object> wrong = map(
                    "id", "wrong-" + System.currentTimeMillis() + "-" + index,
                    "userId", user == null ? null : user.get("id"),
                    "questionId", q.get("id"),
                    "title", title,
                    "type", type,
                    "stem", q.get("stem"),
                    "options", q.get("options"),
                    "answer", q.get("answer"),
                    "selected", selected,
                    "analysis", q.get("analysis"),
                    "knowledge", q.get("knowledge"),
                    "mastered", false,
                    "updatedAt", now()
                );
                wrongQuestions.add(wrong);
            }
            details.add(map("id", q.get("id"), "stem", q.get("stem"), "selected", selected, "answer", q.get("answer"), "correct", ok, "analysis", q.get("analysis")));
            index++;
        }
        Map<String, Object> attempt = map(
            "id", "attempt-" + System.currentTimeMillis(),
            "userId", user == null ? null : user.get("id"),
            "title", title,
            "type", type,
            "total", source.size(),
            "correct", correct,
            "score", source.isEmpty() ? 0 : Math.round(correct * 100f / source.size()),
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
            return questionsByIds(Arrays.asList("q-derivative-1", "q-logic-2"));
        }
        if (title.contains("数列") || title.contains("通项"))
        {
            return questionsByIds(Arrays.asList("q-series-1", "q-logic-1"));
        }
        if (title.contains("集合") || title.contains("逻辑") || title.contains("不等式"))
        {
            return questionsByIds(Arrays.asList("q-logic-1", "q-logic-2"));
        }
        return questions.subList(0, Math.min(3, questions.size()));
    }

    private static List<Map<String, Object>> questionsByIds(List<String> ids)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> q : questions)
        {
            if (ids.contains(q.get("id")))
            {
                list.add(q);
            }
        }
        return list;
    }

    private static List<Map<String, Object>> publicQuestions(List<Map<String, Object>> source)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> q : source)
        {
            Map<String, Object> item = new LinkedHashMap<>(q);
            item.remove("answer");
            list.add(item);
        }
        return list;
    }

    private static Map<String, Object> getStudySummary()
    {
        return map(
            "sections", list(
                map("title", "知识点扫雷", "items", list(map("label", "刷题数", "value", "186道"), map("label", "正确", "value", "142道"), map("label", "正确率", "value", "76%"), map("label", "平均得分", "value", "78分"))),
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
                map("title", "复习情况统计", "items", list(map("label", "复习课程完成情况", "value", "68%"), map("label", "测评统计", "value", "完成6次，平均72分")), "details", list(
                    map("title", "第1次复习试卷", "count", "完成", "score", "70分", "records", list(map("name", "错题复盘", "result", "正确12题，错误5题", "score", "70分"))),
                    map("title", "第2次复习试卷", "count", "完成", "score", "74分", "records", list(map("name", "综合测试", "result", "正确15题，错误4题", "score", "74分")))
                )),
                map("title", "思维技巧", "items", list(map("label", "英语完成度", "value", "30%"), map("label", "训练做题", "value", "96道"), map("label", "正确", "value", "73道"), map("label", "错误", "value", "23道"), map("label", "平均得分", "value", "76分"))),
                map("title", "英语外语科目", "items", list(map("label", "单词完成数量", "value", "428个"), map("label", "今日完成", "value", "36个")))
            ),
            "plateScores", list(
                plate("知识点扫雷", 78),
                plate("章节测评", 74),
                plate("复习情况", 62),
                plate("思维技巧", 86),
                plate("真题讲练", 58)
            )
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
            "subjects", ratingBreakdown("subjectTitle"),
            "chapters", ratingBreakdown("chapterTitle"),
            "lessons", ratingBreakdown("lessonTitle")
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
            "courseTitle", course == null ? str(body.get("courseTitle")) : str(course.get("courseName")),
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
            "courseTitle", course.get("courseName"),
            "status", "activated",
            "amount", "0.00",
            "cardCode", cardCode,
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
                enrollments.add(map("id", "enr-" + System.currentTimeMillis(), "userId", user.get("id"), "courseId", courseId, "expiry", "2026-12-31", "status", "active", "source", source, "cardCode", cardCode));
            }
        }
        return order;
    }

    private static String normalizeCardCode(Object value)
    {
        return str(value).trim().toUpperCase().replace(" ", "");
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
        return item;
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
        if (!course.containsKey("chapters"))
        {
            course.put("chapters", new ArrayList<Object>());
        }
        if (!course.containsKey("quizzes"))
        {
            course.put("quizzes", list(makeQuiz("入门测", "未学习", "去测评")));
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

    private static String str(Object value)
    {
        return value == null ? "" : String.valueOf(value);
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
