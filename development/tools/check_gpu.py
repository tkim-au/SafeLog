"""Quick check that PyTorch sees the RTX 3060 Ti."""
import torch

print("torch", torch.__version__, "| CUDA available:", torch.cuda.is_available())
if torch.cuda.is_available():
    p = torch.cuda.get_device_properties(0)
    print(f"GPU: {p.name}, {p.total_memory / 1024**3:.1f} GB VRAM, compute {p.major}.{p.minor}")
    x = torch.randn(4096, 4096, device="cuda")
    torch.cuda.synchronize()
    print("matmul ok:", float((x @ x).mean()))
