package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("sys_file_parse_log")
public class SysFileParseLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    // 唯一UUID文件编码
    private String fileCode;
    // 物理路径
    private String filePath;

    // 上传人账号名或ID
    private String uploader;
    // 上传人单位/部门
    private String uploaderDept;

    // 上传时间
    private Date uploadTime;
    
    // 提取解析耗时(毫秒)
    private Integer parseDurationMs;
    // 解析结果日志(污损/截断)
    private String parseResult;
    
    // 语义分块及向量化耗时(毫秒)
    private Integer chunkDurationMs;
    // 实际产生的高质分片数
    private Integer chunkCount;
    
    // 成功入库并双写ES的时间
    private Date indexTime;

    // 状态机：0-解析入库中, 1-入库成功, 2-提取解析异常, 3-分块及向量库异常, 4-解析超时中断
    private Integer status;

    private Date createdAt;
    private Date updatedAt;

    @TableLogic
    private Integer isDeleted;
}
