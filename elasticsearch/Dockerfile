# 基于官方 ES 8.6.2 镜像，离线预装 IK 中文分词插件
FROM docker.elastic.co/elasticsearch/elasticsearch:8.6.2

# 将预先下载好的 IK 插件包复制进容器
COPY elasticsearch-analysis-ik-8.6.2.zip /tmp/elasticsearch-analysis-ik-8.6.2.zip

# 安装 IK 分词器（--batch 跳过交互确认）
RUN yes | elasticsearch-plugin install file:///tmp/elasticsearch-analysis-ik-8.6.2.zip


