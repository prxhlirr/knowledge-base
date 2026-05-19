import zipfile
import os

def check_file(file_path):
    print(f"检查文件: {file_path}")
    if not os.path.exists(file_path):
        print("❌ 错误: 文件不存在")
        return

    print(f"文件大小: {os.path.getsize(file_path)} 字节")
    
    # docx 文件本质上是一个 zip 包
    try:
        with zipfile.ZipFile(file_path) as z:
            print("✅ 文件是一个有效的 ZIP 压缩包 (docx 结构正确)")
            print("压缩包内部分文件:")
            for f in z.namelist()[:5]:
                print(f" - {f}")
    except zipfile.BadZipFile:
        print("❌ 错误: 文件不是有效的 ZIP 压缩包!")
        print("💡 可能原因: 您直接将 .doc 修改为了 .docx 后缀，而不是通过 Word 的 '另存为' 功能转换。")
        print("💡 解决方法: 请在 Word 中打开文件，选择 '文件' -> '另存为' -> 'Word 文档 (*.docx)'。")

if __name__ == "__main__":
    path = r"E:\project\AI\knowledge-base\mock\中华人民共和国预算法实施条例.docx"
    check_file(path)
