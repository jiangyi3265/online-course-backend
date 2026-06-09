package com.ruoyi.common.utils.file;

/**
 * 媒体类型工具类
 * 
 * @author ruoyi
 */
public class MimeTypeUtils
{
    public static final String IMAGE_PNG = "image/png";

    public static final String IMAGE_JPG = "image/jpg";

    public static final String IMAGE_JPEG = "image/jpeg";

    public static final String IMAGE_BMP = "image/bmp";

    public static final String IMAGE_GIF = "image/gif";

    public static final String AUDIO_MPEG = "audio/mpeg";

    public static final String AUDIO_MP3 = "audio/mp3";

    public static final String AUDIO_MP4 = "audio/mp4";

    public static final String AUDIO_X_M4A = "audio/x-m4a";

    public static final String AUDIO_WAV = "audio/wav";

    public static final String AUDIO_X_WAV = "audio/x-wav";

    public static final String AUDIO_AAC = "audio/aac";

    public static final String AUDIO_OGG = "audio/ogg";
    
    public static final String[] IMAGE_EXTENSION = { "bmp", "gif", "jpg", "jpeg", "png" };

    public static final String[] FLASH_EXTENSION = { "swf", "flv" };

    public static final String[] MEDIA_EXTENSION = { "swf", "flv", "mp3", "m4a", "wav", "aac", "ogg", "wma", "wmv",
            "mid", "avi", "mpg", "asf", "rm", "rmvb" };

    public static final String[] VIDEO_EXTENSION = { "mp4", "mov", "m4v", "webm", "avi", "rmvb" };

    public static final String[] DEFAULT_ALLOWED_EXTENSION = {
            // 图片
            "bmp", "gif", "jpg", "jpeg", "png",
            // word excel powerpoint
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm", "txt",
            // 压缩文件
            "rar", "zip", "gz", "bz2",
            // 视频格式
            "mp4", "mov", "m4v", "webm", "avi", "rmvb",
            // 音频格式
            "mp3", "m4a", "wav", "aac", "ogg",
            // pdf
            "pdf" };

    public static String getExtension(String prefix)
    {
        switch (prefix)
        {
            case IMAGE_PNG:
                return "png";
            case IMAGE_JPG:
                return "jpg";
            case IMAGE_JPEG:
                return "jpeg";
            case IMAGE_BMP:
                return "bmp";
            case IMAGE_GIF:
                return "gif";
            case AUDIO_MPEG:
            case AUDIO_MP3:
                return "mp3";
            case AUDIO_MP4:
            case AUDIO_X_M4A:
                return "m4a";
            case AUDIO_WAV:
            case AUDIO_X_WAV:
                return "wav";
            case AUDIO_AAC:
                return "aac";
            case AUDIO_OGG:
                return "ogg";
            default:
                return "";
        }
    }
}
