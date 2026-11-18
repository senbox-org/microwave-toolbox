# Faithful port of PolarimetricCalibrationOp estimateDistortion / computeInverseDistortion (lines 176-385)
import numpy as np
rng=np.random.default_rng(0); N=200000
def cg(p): return np.sqrt(p/2)*(rng.standard_normal(N)+1j*rng.standard_normal(N))
hh=cg(1); vv=0.5*hh+np.sqrt(0.75)*cg(1); hv=cg(10**-1.5)
k=np.array([hh,hv,hv,vv])            # k=[HH,HV,VH,VV], reciprocal, reflection symmetric
def buildD(f,d):
    R=np.array([[1,d[3]],[d[2],f]]); T=np.array([[1,d[1]],[d[0],f]]); return np.kron(R,T)
def run(iters, dtrue, ftrue=1.0):
    M=buildD(ftrue,dtrue)@k
    C=(M@M.conj().T)/N
    f=1.0+0j; d=np.zeros(4,complex)
    for it in range(iters):
        s11=C[0,0].real; s44=C[3,3].real
        amp=np.sqrt(s44/s11); ph=0.5*np.angle(C[0,3]); f=f*amp*np.exp(1j*ph)
        invF=1/f
        C[3,:]=C[3,:]*invF; C[:,3]=C[:,3]*np.conj(invF)
        f=1.0+0j                                   # line 328 reset
        s11=C[0,0].real; s44=C[3,3].real
        d+=np.array([C[0,2]/s11, C[0,1]/s11, C[2,3]/s44, C[1,3]/s44])
        if it+1<iters:
            Di=np.linalg.inv(buildD(f,d)); C=Di@C@Di.conj().T
    S=np.linalg.inv(buildD(f,d))@M
    def corr(a,b): return abs(np.mean(a*np.conj(b)))/np.sqrt(np.mean(abs(a)**2)*np.mean(abs(b)**2))
    return f,d,corr(S[0],S[1]),corr(M[0],M[1]),10*np.log10(np.mean(abs(S[3])**2)/np.mean(abs(S[0])**2))
dt=10**(-25/20)*np.exp(1j*np.array([0.3,1.1,2.0,-0.7]))
for it in (1,2,3,4):
    f,d,cs,cm,r=run(it,dt)
    print(f"iters={it}: f_out={f:.3f}  |d_est|/|d_true|={np.round(abs(d)/abs(dt),2)}  phase(d_est)-phase(d_true)={np.round(np.angle(d/dt),2)}  corr(HH,HV) in {cm:.3f} -> out {cs:.3f}")
f,d,cs,cm,r=run(4,np.zeros(4),ftrue=np.sqrt(1.5)*np.exp(1j*0.3))
print(f"imbalance only (VV gain f^2=1.5 amp, phase 0.6 rad): f_out={f}, VV/HH power out {r:+.2f} dB (in {10*np.log10(1.5**2):+.2f} dB)")
print("--- physical model M = R S T (rows=receive), vec = kron(R, T^T); 5 random phase draws, -25 dB")
def run_phys(iters, dt):
    R=np.array([[1,dt[0]],[dt[1],1]]); T=np.array([[1,dt[2]],[dt[3],1]])
    M=np.kron(R,T.T)@k
    C=(M@M.conj().T)/N; f=1+0j; d=np.zeros(4,complex)
    for it in range(iters):
        s11=C[0,0].real; s44=C[3,3].real
        f=f*np.sqrt(s44/s11)*np.exp(0.5j*np.angle(C[0,3])); invF=1/f
        C[3,:]*=invF; C[:,3]*=np.conj(invF); f=1+0j
        s11=C[0,0].real; s44=C[3,3].real
        d+=np.array([C[0,2]/s11, C[0,1]/s11, C[2,3]/s44, C[1,3]/s44])
        if it+1<iters:
            Di=np.linalg.inv(buildD(f,d)); C=Di@C@Di.conj().T
    S=np.linalg.inv(buildD(f,d))@M
    xt=lambda X: 10*np.log10((abs(np.mean(X[0]*np.conj(X[1])))**2+abs(np.mean(X[0]*np.conj(X[2])))**2)/2/np.mean(abs(X[0])**2)**2)
    return xt(M), xt(S)
for s in range(5):
    dt=10**(-25/20)*np.exp(1j*rng.uniform(0,2*np.pi,4))
    print("  draw",s," ".join(f"it{it}: {run_phys(it,dt)[0]:.1f}->{run_phys(it,dt)[1]:.1f} dB" for it in (1,4)))
