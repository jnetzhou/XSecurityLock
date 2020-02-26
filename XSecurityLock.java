package com.jnetzhou.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 
 * @author jnetzhou
 * @description 带死锁及超时检测的锁辅助类
 */
public class XSecurityLock {
	
	private static class XLockHolder {
		/**
		 * 请求XLock的线程映射表
		 */
		private final static HashMap<Thread,XLock> tlRequire = new HashMap<Thread,XLock>();
		private final static HashMap<XLock,Thread> ltRequire = new HashMap<XLock,Thread>();
		/**
		 * 持有XLock的线程映射表
		 */
		private final static HashMap<Thread,XLock> tlHold = new HashMap<Thread,XLock>();
		private final static HashMap<XLock,Thread> ltHold = new HashMap<XLock,Thread>();
		
		private static ScheduledExecutorService scheduledExecutorService = null;
				
		//private static DeadLockCallback deadLockCallback = null;
		private final static HashMap<String,DeadLockCallback> deadLockCallbackMap = new HashMap<String,DeadLockCallback>();
		
		public static interface DeadLockCallback{
			public void onDeadLock(final Set<Thread> waitingThreads,final HashMap<Thread,XLock> tlRequire,final HashMap<Thread,XLock> tlHold);
		}
		
		public static void setDeadLockCallback(final String lockName,final DeadLockCallback deadLockCb) {
			//deadLockCallback = deadLockCb;
			deadLockCallbackMap.put(lockName, deadLockCb);
		}
		
		public static void unsetDeadLockCallback(final String lockName) {
			deadLockCallbackMap.remove(lockName);
		}
		
		public static interface RequireLockTimeoutCallback {
			public void onTimeout(final Thread thread,final XLock lock);
		}
		
		public static class RequireLockTimeRecord {
			public Thread requireThread;
			public XLock requireLock;
			public long requireTime;
			public RequireLockTimeoutCallback onRequiredTimeoutCb;
			
			public RequireLockTimeRecord(final Thread thread,final XLock lock,final RequireLockTimeoutCallback onTimeoutCb) {
				this.requireThread = thread;
				this.requireLock = lock;
				this.requireTime = System.currentTimeMillis();
				this.onRequiredTimeoutCb = onTimeoutCb;
			}
			
			public RequireLockTimeRecord(final Thread thread,final XLock lock,final long time,final RequireLockTimeoutCallback onTimeoutCb) {
				this.requireThread = thread;
				this.requireLock = lock;
				this.requireTime = time;
				this.onRequiredTimeoutCb = onTimeoutCb;
			}
		}
		
		private final static ArrayList<RequireLockTimeRecord> requireRecords = new ArrayList<RequireLockTimeRecord>();
		
		public synchronized static void registerRequireLockTimeRecord(final Thread thread,final XLock lock,final RequireLockTimeoutCallback onTimeoutCb) {
			requireRecords.add(new RequireLockTimeRecord(thread,lock,onTimeoutCb));
		}
		
		public synchronized static void registerRequireLockTimeRecord(final Thread thread,final XLock lock,final long time,final RequireLockTimeoutCallback onTimeoutCb) {
			requireRecords.add(new RequireLockTimeRecord(thread,lock,time,onTimeoutCb));
		}
		
		public static void unregisterRequireLockTimeRecord(final RequireLockTimeRecord requireLockTimeRecord) {
			synchronized(requireRecords) {
				if(requireRecords.contains(requireLockTimeRecord)) {
					requireRecords.remove(requireRecords);
				}
			}
		}
		
		public static void unregisterRequireLockTimeRecord(final Thread thread,final XLock lock) {
			synchronized(requireRecords) {
				for(RequireLockTimeRecord requireLockTimeRecord : requireRecords) {
					if(requireLockTimeRecord.requireThread == thread && requireLockTimeRecord.requireLock == lock) {
						requireRecords.remove(lock);
					}
				}
			}
		}
		
		public static void checkupTimeoutRequireLockRecord(final long timeout) {
			if(scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
				scheduledExecutorService.isShutdown();
				
			}
			scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
			scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

				@Override
				public void run() {
					// TODO Auto-generated method stub
					synchronized(requireRecords) {
						for(RequireLockTimeRecord requireLockTimeRecord : requireRecords) {
							if(System.currentTimeMillis() - requireLockTimeRecord.requireTime > timeout) {
								if(requireLockTimeRecord.onRequiredTimeoutCb != null) {
									requireLockTimeRecord.onRequiredTimeoutCb.onTimeout(requireLockTimeRecord.requireThread,requireLockTimeRecord.requireLock);
								}
							}
						}
					}
				}
				
			}, 2000, 5000, TimeUnit.MILLISECONDS);
		}
		
		public synchronized static Object[] checkDeadLock(Thread requester,XLock requireLock) {
			// TODO Auto-generated method stub
			HashSet<Thread> requesters = new HashSet<Thread>();
			requesters.add(requester);
			boolean isDeadLock = recursiveCheckDeadLock(requester,requireLock,requesters);
			Object[] result = new Object[2];
			result[0] = isDeadLock;
			result[1] = requesters;
			return result;
		}
		
		
		private static void printThreadLockCallStack(final Set<Thread> threads,HashMap<Thread,XLock> tlHold,HashMap<Thread,XLock> tlRequire) {
			if(threads != null && threads.size() > 0) {
				StringBuilder sb = new StringBuilder();
				XLock holdLock,requireLock;
				for(Thread thread : threads) {
					holdLock = tlHold.get(thread);
					requireLock = tlRequire.get(thread);
					sb.append(String.format("Thread[%s] Hold Lock[%s] Require Lock[%s] \n", thread.getName(),holdLock == null ? "null" : holdLock.getName(),requireLock == null ? "null" : requireLock.getName()));
				}
				System.err.println(sb.toString());
			}
		}
		
		/**
		 * 递归检测锁请求队列
		 * @param requester
		 * @param requireLock
		 * @param requesters
		 * @return
		 */
		private synchronized static boolean recursiveCheckDeadLock(Thread requester, XLock requireLock, Set<Thread> requesters) {
			//持有requireLock的线程
			Thread holderThread = XLockHolder.ltHold.get(requireLock);
			if(holderThread == null) {
				return false;			//非死锁
			}
			//等待锁的队列中出现回路，死锁
			if(requesters.contains(holderThread)) {
				return true;
			}
			// holderThread线程在请求的锁
			XLock reqLock = XLockHolder.tlRequire.get(holderThread);	
			if(reqLock != null) {
				requesters.add(holderThread);
				return recursiveCheckDeadLock(holderThread,reqLock,requesters);
			} else {
				return false;
			}
		}
		
		public synchronized static void beforeLock(final Thread thread,final XLock lock) {
			// TODO Auto-generated method stub
				XLockHolder.tlRequire.put(Thread.currentThread(), lock);
				XLockHolder.ltRequire.put(lock,Thread.currentThread());
				registerRequireLockTimeRecord(thread, lock, new RequireLockTimeoutCallback() {
					
					@Override
					public void onTimeout(Thread thread, XLock lock) {
						// TODO Auto-generated method stub
						System.err.println(String.format("Thread[%s]Get Lock[%s]Timeout...,",thread.getName(),lock.getName()));
					}
				});
				Object[] result = checkDeadLock(thread,lock);
				if(((Boolean)result[0])) {
					System.err.println(String.format("Thread[%s]Get Lock[%s]Dead Lock,reference as following:\n", thread.getName(),lock.getName()));
					if(XLockHolder.deadLockCallbackMap.containsKey(lock.getName())) {
						XLockHolder.deadLockCallbackMap.get(lock.getName()).onDeadLock((Set<Thread>)result[1], tlRequire, tlHold);
					}
				}
		}
		
		public synchronized static void afterLock(final Thread thread,final XLock lock) {
			// TODO Auto-generated method stub
			XLockHolder.tlRequire.remove(thread);
			XLockHolder.ltRequire.remove(lock);
			XLockHolder.tlHold.put(thread, lock);
			XLockHolder.ltHold.put(lock, thread);
		}
		
		public synchronized static void beforeUnlock(final Thread thread,final XLock lock) {
			// TODO Auto-generated method stub
			
		}
		
		public synchronized static void afterUnlock(final Thread thread,final XLock lock) {
			// TODO Auto-generated method stub
			XLockHolder.tlRequire.remove(thread);
			XLockHolder.ltRequire.remove(lock);
			XLockHolder.tlHold.remove(thread);
			XLockHolder.ltHold.remove(lock);
		}
	}
	
	private static abstract class XLock{
		protected String name;
		protected java.util.concurrent.locks.Lock lockImpl;
		public XLock(final String name,final java.util.concurrent.locks.Lock lockImpl) {
			this.name = name;
			this.lockImpl = lockImpl;
		}
		public void setName(String name) {this.name = name;}
		public String getName() {return this.name;}
		public abstract void lock();
		public abstract void unlock();
	}

	private static class XLockImpl extends XLock{
		
		public XLockImpl(String name,java.util.concurrent.locks.Lock lockImp) {
			super(name,lockImp);
			// TODO Auto-generated constructor stub
		}

		@Override
		public synchronized void lock() {
			// TODO Auto-generated method stub
			XLockHolder.beforeLock(Thread.currentThread(),this);
			lockImpl.lock();
			XLockHolder.afterLock(Thread.currentThread(),this);
		}
		
		@Override
		public synchronized void unlock() {
			// TODO Auto-generated method stub
			XLockHolder.beforeUnlock(Thread.currentThread(),this);
			lockImpl.unlock();
			XLockHolder.afterUnlock(Thread.currentThread(),this);
		}
		
	}
	
	private XLockImpl xLockImpl;

	public XSecurityLock(final String name,final java.util.concurrent.locks.Lock lockImpl) {
		xLockImpl = new XLockImpl(name,lockImpl);
		//check require lock timeout
		XLockHolder.checkupTimeoutRequireLockRecord(10000);
		XLockHolder.setDeadLockCallback(name,new com.xbull.utils.XSecurityLock.XLockHolder.DeadLockCallback() {
			
			@Override
			public void onDeadLock(Set<Thread> waitingThreads, HashMap<Thread, XLock> tlRequire,
					HashMap<Thread, XLock> tlHold) {
				// TODO Auto-generated method stub
				XLockHolder.printThreadLockCallStack(waitingThreads, tlHold,tlRequire);
			}
		});
	}
	
	public XSecurityLock(final java.util.concurrent.locks.Lock lockImpl) {
		this("XSecurityLock",lockImpl);
	}
	
	public void lock() {xLockImpl.lock();}
	
	public void unlock() {xLockImpl.unlock();}
	
	public static void main(String[] args) {
		final XSecurityLock lock1 = new XSecurityLock("ReentrantLock1",new java.util.concurrent.locks.ReentrantLock());
		final XSecurityLock lock2 = new XSecurityLock("ReentrantLock2",new java.util.concurrent.locks.ReentrantLock());
		final XSecurityLock lock3 = new XSecurityLock("ReentrantLock3",new java.util.concurrent.locks.ReentrantLock());
		new Thread(new Runnable() {
			@Override
			public void run() {
				System.out.println("Thread One...");
				lock1.lock();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				// lock1.unLock();
				lock2.lock();
				System.out.println("1.......");
			}
		}).start();

		new Thread(new Runnable() {
			@Override
			public void run() {
				System.out.println("Thread Two...");
				lock2.lock();
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				lock3.lock();
				lock2.unlock();
				System.out.println("2......");
			}
		}).start();
		
		new Thread(new Runnable() {
			@Override
			public void run() {
				System.out.println("Thread Three...");
				lock3.lock();
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				lock1.lock();
				lock3.unlock();
				System.out.println("3......");
			}
		}).start();

	}

	
}
