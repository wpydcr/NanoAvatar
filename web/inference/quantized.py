"""Runtime for native_cuda_integer_v1; bundled DLL targets Windows CUDA SM89.

HuBERT W8A16 uses signed INT16 activations and two INT8 Tensor Core products.
Reuses the calibrated native runtime, without build or calibration tools.
"""
import ctypes as ct
from pathlib import Path

import torch
from torch import nn


def padded(value, minimum=8):
    return max(minimum, (value+7)//8*8)


class Kernels:
    def __init__(self, path):
        self.path = Path(path).resolve()
        try:
            self.dll = ct.CDLL(str(self.path))
        except OSError as error:
            raise RuntimeError(
                "Native integer models require native_quant_cuda.dll and its CUDA runtime; "
                "the bundled DLL targets Windows CUDA SM89 (RTX 4090).") from error
        ptr, integer, floating = ct.c_void_p, ct.c_int, ct.c_float
        signatures = {
            'pack_i16':[ptr]*4+[integer]*5+[ptr],
            'finish_i16':[ptr]*7+[integer]*4+[ptr],
            'pack_conv_i8':[ptr]*2+[floating]+[integer]*17+[ptr],
            'finish_conv_i8':[ptr]*4+[floating]+[integer]*6+[ptr],
            'pack_linear_i8':[ptr]*2+[floating]+[integer]*5+[ptr],
            'finish_linear_i8':[ptr]*4+[floating]+[integer]*4+[ptr],
        }
        for name,signature in signatures.items():
            function = getattr(self.dll,name)
            function.argtypes, function.restype = signature, integer

    def call(self, name, *args):
        status = getattr(self.dll,name)(*[value.data_ptr() if isinstance(value,torch.Tensor) else value for value in args],
            torch.cuda.current_stream().cuda_stream)
        if status:
            raise RuntimeError(f'{name}: CUDA error {status}')

    @staticmethod
    def check(x):
        if x.device.type != 'cuda' or x.dtype not in (torch.float16,torch.float32):
            raise ValueError('Quantized primitives require FP16/FP32 CUDA inputs')
        return x.contiguous(), int(x.dtype==torch.float16)

    def linear(self, x, weights, scales, sums, bias, features, mode, activation_scale):
        x, fp16 = self.check(x)
        k, n = x.shape[-1], features
        m = x.numel()//k
        mp, kp, np = padded(m,32), weights.shape[1], weights.shape[0]
        output = torch.empty((*x.shape[:-1],n),device=x.device,dtype=x.dtype)
        packed = torch.empty((mp,kp),device=x.device,dtype=torch.int8)
        if mode == 'w8a16':
            low = torch.empty_like(packed)
            a_scales = torch.empty((mp,),device=x.device,dtype=torch.float32)
            self.call('pack_i16',x,packed,low,a_scales,m,k,mp,kp,fp16)
            high_dot = torch._int_mm(packed,weights.T)
            low_dot = torch._int_mm(low,weights.T)
            self.call('finish_i16',high_dot,low_dot,a_scales,scales,sums,bias,output,m,n,np,fp16)
        else:
            self.call('pack_linear_i8',x,packed,activation_scale,m,k,mp,kp,fp16)
            product = torch._int_mm(packed,weights.T)
            self.call('finish_linear_i8',product,scales,bias,output,activation_scale,m,n,np,fp16)
        return output

    def conv(self, x, weights, scales, bias, shape, activation_scale):
        x, fp16 = self.check(x)
        batch,channels,ih,iw = x.shape
        n,kh,kw,sh,sw,ph,pw,dh,dw = shape
        oh,ow = (ih+2*ph-dh*(kh-1)-1)//sh+1, (iw+2*pw-dw*(kw-1)-1)//sw+1
        mp,kp,np = padded(batch*oh*ow,32), weights.shape[1], weights.shape[0]
        packed = torch.empty((mp,kp),device=x.device,dtype=torch.int8)
        self.call('pack_conv_i8',x,packed,activation_scale,batch,channels,ih,iw,oh,ow,kh,kw,sh,sw,ph,pw,dh,dw,mp,kp,fp16)
        product = torch._int_mm(packed,weights.T)
        output = torch.empty((batch,n,oh,ow),device=x.device,dtype=x.dtype)
        self.call('finish_conv_i8',product,scales,bias,output,activation_scale,batch,n,np,oh,ow,fp16)
        return output


class QuantizedLayer(nn.Module):
    def __init__(self, specification, kernels, device='cpu'):
        super().__init__()
        self.specification, self.kernels = dict(specification), kernels
        n,k = specification['out_features'], specification['in_features']
        self.register_buffer('weight_int8',torch.empty((padded(n),padded(k)),device=device,dtype=torch.int8))
        self.register_buffer('weight_scale',torch.empty((padded(n),),device=device,dtype=torch.float32))
        self.register_buffer('weight_sum',torch.empty((padded(n),),device=device,dtype=torch.int32))
        self.register_buffer('bias_float',torch.empty((padded(n),),device=device,dtype=torch.float32))

    def forward(self, x):
        spec = self.specification
        if 'conv_shape' in spec:
            return self.kernels.conv(x,self.weight_int8,self.weight_scale,self.bias_float,
                spec['conv_shape'],spec['activation_scale'])
        return self.kernels.linear(x,self.weight_int8,self.weight_scale,self.weight_sum,self.bias_float,
            spec['out_features'],spec['mode'],spec['activation_scale'])


def replace_modules(model, specifications, kernels):
    for name,specification in specifications.items():
        previous = model.get_submodule(name)
        parent_name,_,child_name = name.rpartition('.')
        parent = model.get_submodule(parent_name) if parent_name else model
        setattr(parent,child_name,QuantizedLayer(specification,kernels,previous.weight.device))
    return model
